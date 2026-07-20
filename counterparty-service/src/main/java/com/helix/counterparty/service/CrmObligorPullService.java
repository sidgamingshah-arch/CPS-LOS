package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.IngestionGuard;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.CrmPullDtos.CrmPullBatchSummary;
import com.helix.counterparty.dto.CrmPullDtos.CrmPullResult;
import com.helix.counterparty.dto.IngestDtos.RawCrmPayload;
import com.helix.counterparty.dto.InitiationDtos.CreateProspectRequest;
import com.helix.counterparty.dto.InitiationDtos.DedupResult;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.repo.CounterpartyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CRM as the primary obligor-creation system (PRD §8 pull-and-create). Pulls borrower(s) from
 * CRM (simulated by default, live via env base-url) and CREATES them as governed prospects —
 * so a bank can run CRM as its system-of-record for obligor creation.
 *
 * <p><b>Governance (non-negotiable):</b> creation ALWAYS routes through the existing governed
 * credit-initiation flow ({@link InitiationService#createProspect}). A pull produces a
 * <b>PROSPECT</b>, never a fully-created/approved obligor — dedup, negative-check, RM ownership
 * and audit all fire exactly as for a hand-entered prospect, and a named human must still promote
 * prospect → obligor via {@code /prospects/{id}/approve}. This service never calls approve.
 *
 * <p>The upsert is:
 * <ol>
 *   <li><b>Idempotency</b> on the CRM id (via {@link IngestionGuard}) — re-pulling the same CRM
 *       id returns the already-created counterparty ({@code matchedExisting=true, created=false});
 *       no duplicate is ever created.</li>
 *   <li><b>Dedup</b> against identifiers before creating — a match links/enriches the existing
 *       counterparty instead of duplicating ({@code created=false, dedupMatches>0}).</li>
 *   <li><b>Create</b> otherwise, mapping CRM fields → {@code CreateProspectRequest} and calling
 *       {@code createProspect} unchanged; the CRM profile is then attached for provenance.</li>
 *   <li><b>Negative check</b> — a negative-list hit is flagged ({@code negativeHit=true}) but the
 *       prospect is NEVER auto-approved; a human decides.</li>
 * </ol>
 */
@Service
public class CrmObligorPullService {

    private static final String GUARD_PREFIX = "CRM-OBLIGOR-";

    private final CrmFetchSource fetchSource;
    private final InitiationService initiation;
    private final CrmIngestionService crmIngestion;
    private final CounterpartyRepository counterparties;
    private final IngestionGuard guard;
    private final AuditService audit;

    public CrmObligorPullService(CrmFetchSource fetchSource, InitiationService initiation,
                                 CrmIngestionService crmIngestion, CounterpartyRepository counterparties,
                                 IngestionGuard guard, AuditService audit) {
        this.fetchSource = fetchSource;
        this.initiation = initiation;
        this.crmIngestion = crmIngestion;
        this.counterparties = counterparties;
        this.guard = guard;
        this.audit = audit;
    }

    /** Pull a single borrower from CRM by its CRM id and upsert as a governed prospect. */
    @Transactional
    public CrmPullResult pullBorrower(String crmId, String actor) {
        Envelope<RawCrmPayload> envelope = fetchSource.fetchForCreate(crmId);
        return upsert(envelope, actor);
    }

    /** Pull a batch of borrowers from CRM (or the default sample list when no ids given). */
    @Transactional
    public CrmPullBatchSummary pullBatch(List<String> crmIds, String actor) {
        List<Envelope<RawCrmPayload>> envelopes = fetchSource.fetchBatchForCreate(crmIds);
        List<CrmPullResult> results = new ArrayList<>();
        int created = 0, matchedExisting = 0, dedupLinked = 0, negativeFlagged = 0;
        for (Envelope<RawCrmPayload> env : envelopes) {
            CrmPullResult r = upsert(env, actor);
            results.add(r);
            if (r.created()) created++;
            if (r.matchedExisting()) matchedExisting++;
            if (!r.created() && !r.matchedExisting() && r.dedupMatches() > 0) dedupLinked++;
            if (r.negativeHit()) negativeFlagged++;
        }
        return new CrmPullBatchSummary(results.size(), created, matchedExisting, dedupLinked,
                negativeFlagged, results);
    }

    // --------------------------------------------------------------------- upsert

    private CrmPullResult upsert(Envelope<RawCrmPayload> envelope, String actor) {
        RawCrmPayload raw = envelope.payload();
        String crmId = raw.crmId();
        if (crmId == null || crmId.isBlank()) {
            throw ApiException.badRequest("CRM payload carries no crmId — cannot pull a borrower");
        }
        String guardKey = GUARD_PREFIX + crmId;

        // 1) Idempotency on the CRM id — re-pull returns the already-created counterparty.
        var prior = guard.priorIngestion(SourceSystem.CRM, guardKey);
        if (prior.isPresent()) {
            Counterparty existing = counterparties.findByReference(prior.get().getCanonicalRef()).orElse(null);
            if (existing != null) {
                return result(existing, false, true, 0, negativeHit(existing), crmId,
                        "Idempotent re-pull — counterparty already created from this CRM id");
            }
            // canonicalRef no longer resolvable — fall through and recreate defensively.
        }

        // 2) Dedup against identifiers BEFORE creating — link/enrich a match instead of duplicating.
        DedupResult dedup = initiation.dedupCandidate(candidateFrom(raw));
        if (dedup.matchCount() > 0) {
            Counterparty matched = counterparties.findById(dedup.matches().get(0).id())
                    .orElseThrow(() -> ApiException.notFound("dedup match no longer present"));
            crmIngestion.ingest(matched.getId(), envelope, actor);   // attach CRM provenance to the existing one
            guard.record(SourceSystem.CRM, guardKey, matched.getReference(),
                    "CRM pull linked to existing counterparty (dedup) crmId=" + crmId);
            audit.human(actor, "CRM_BORROWER_PULLED", "Counterparty", matched.getReference(),
                    "CRM borrower crmId=%s linked to existing counterparty (dedup %d match(es)) — no duplicate created"
                            .formatted(crmId, dedup.matchCount()),
                    Map.of("crmId", crmId, "created", false, "matchedExisting", false,
                            "dedupMatches", dedup.matchCount(), "matchedRef", matched.getReference()));
            return result(matched, false, false, dedup.matchCount(), negativeHit(matched), crmId,
                    "Linked to existing counterparty via dedup (no duplicate created)");
        }

        // 3) Create through the GOVERNED credit-initiation flow — never a bypass.
        Counterparty prospect = initiation.createProspect(toCreateRequest(raw), actor);
        // Attach the CRM profile (provenance) via the existing ingestion service.
        crmIngestion.ingest(prospect.getId(), envelope, actor);

        // 4) Negative check — flag but NEVER auto-approve; a human decides.
        boolean negativeHit = negativeHit(prospect);

        guard.record(SourceSystem.CRM, guardKey, prospect.getReference(),
                "CRM pull created governed prospect crmId=" + crmId);
        audit.human(actor, "CRM_BORROWER_PULLED", "Counterparty", prospect.getReference(),
                "Pulled CRM borrower crmId=%s -> governed PROSPECT %s (negativeHit=%s) — human still promotes to obligor"
                        .formatted(crmId, prospect.getLegalName(), negativeHit),
                Map.of("crmId", crmId, "created", true, "matchedExisting", false,
                        "negativeHit", negativeHit, "lifecycleStatus", String.valueOf(prospect.getLifecycleStatus()),
                        "recordType", String.valueOf(prospect.getRecordType())));

        return result(prospect, true, false, 0, negativeHit, crmId,
                negativeHit
                        ? "Governed prospect created; negative-list HIT — NOT auto-approved (human decision required)"
                        : "Governed prospect created via credit-initiation flow (human promotes to obligor)");
    }

    // --------------------------------------------------------------------- mapping helpers

    /** Transient (unsaved) candidate carrying just the fields dedup reads. */
    private Counterparty candidateFrom(RawCrmPayload raw) {
        Counterparty c = new Counterparty();
        c.setLegalName(legalName(raw));
        c.setRegistrationNo(raw.registrationNo());
        c.setPan(CounterpartyService.normalizeIdentifier(raw.pan()));
        c.setGstin(CounterpartyService.normalizeIdentifier(raw.gstin()));
        c.setLei(CounterpartyService.normalizeIdentifier(raw.lei()));
        c.setCin(CounterpartyService.normalizeIdentifier(raw.cin()));
        return c;
    }

    private CreateProspectRequest toCreateRequest(RawCrmPayload raw) {
        String legalName = legalName(raw);
        if (legalName == null || legalName.isBlank()) {
            throw ApiException.badRequest(
                    "CRM borrower crmId=" + raw.crmId() + " has no legalName/accountName — cannot create prospect");
        }
        String jurisdiction = notBlank(raw.jurisdiction()) ? raw.jurisdiction() : "IN-RBI";
        String segment = notBlank(raw.segment()) ? raw.segment() : "CORPORATE";
        return new CreateProspectRequest(
                legalName,
                null,                       // legalForm
                raw.registrationNo(),
                raw.pan(), raw.gstin(), raw.lei(), raw.cin(),
                jurisdiction,
                segment,
                null, null, null, null, null,   // sector, industry, subIndustry, businessSegment, subSegment
                raw.country(),
                raw.borrowerType(),
                false, false, false, false);    // pep, adverseMedia, highRiskJurisdiction, complexOwnership
    }

    private boolean negativeHit(Counterparty cp) {
        return initiation.negativeCheck(cp.getLegalName(), cp.getCountry()).hit();
    }

    private CrmPullResult result(Counterparty cp, boolean created, boolean matchedExisting,
                                 int dedupMatches, boolean negativeHit, String crmId, String message) {
        return new CrmPullResult(cp.getReference(), cp.getId(), created, matchedExisting, dedupMatches,
                negativeHit, cp.getLifecycleStatus(), cp.getRecordType(), crmId, message);
    }

    private static String legalName(RawCrmPayload raw) {
        return notBlank(raw.legalName()) ? raw.legalName() : raw.accountName();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
