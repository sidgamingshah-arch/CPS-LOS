package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.common.ingest.IngestionGuard;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.IngestDtos.RawCrmPayload;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.CrmProfile;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.CrmProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Ingests an inbound CRM feed via the canonical connector contract (PRD §8): idempotent,
 * provenance-stamped, failures surfaced as warnings — mirroring {@link ScreeningIngestionService}.
 *
 * <p><b>Governance:</b> the ingested profile is stored purely as INPUT / provenance data on an
 * advisory {@link CrmProfile}. It does NOT mutate an authoritative figure — the counterparty row
 * is never touched here.
 */
@Service
public class CrmIngestionService {

    private final CrmInboundConnector connector;
    private final CounterpartyRepository counterparties;
    private final CrmProfileRepository profiles;
    private final CrmFetchSource fetchSource;
    private final IngestionGuard guard;
    private final AuditService audit;

    public CrmIngestionService(CrmInboundConnector connector, CounterpartyRepository counterparties,
                               CrmProfileRepository profiles, CrmFetchSource fetchSource,
                               IngestionGuard guard, AuditService audit) {
        this.connector = connector;
        this.counterparties = counterparties;
        this.profiles = profiles;
        this.fetchSource = fetchSource;
        this.guard = guard;
        this.audit = audit;
    }

    /** PUSH: an external caller POSTs a raw vendor payload. */
    @Transactional
    public Result ingest(Long counterpartyId, Envelope<RawCrmPayload> envelope, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        String key = envelope.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }

        var prior = guard.priorIngestion(SourceSystem.CRM, key);
        if (prior.isPresent()) {
            return Result.duplicate(SourceSystem.CRM, key, prior.get().getCanonicalRef());
        }

        var warnings = connector.validate(envelope.payload());
        Provenance prov = Provenance.of(SourceSystem.CRM, envelope.vendor(), key, envelope.payloadVersion());
        Canonical.CrmProfile canonical = connector.map(envelope.payload(), prov);

        CrmProfile rec = new CrmProfile();
        rec.setCounterpartyId(cp.getId());
        rec.setCrmId(canonical.crmId());
        rec.setAccountName(canonical.accountName());
        rec.setRelationshipManager(canonical.relationshipManager());
        rec.setSegment(canonical.segment());
        rec.setRelationshipValue(canonical.relationshipValue());
        rec.setPrimaryContactName(canonical.primaryContactName());
        rec.setPrimaryContactEmail(canonical.primaryContactEmail());
        rec.setProductsHeld(canonical.productsHeld());
        rec.setLifecycleStage(canonical.lifecycleStage());
        rec.setSourceSystem(prov.sourceSystem().name());
        rec.setSourceVendor(prov.vendor());
        rec.setSourceReference(prov.sourceReference());
        rec.setPayloadVersion(prov.payloadVersion());
        rec.setRetrievedAt(prov.retrievedAt());
        rec.setAdvisory(true);
        profiles.save(rec);

        guard.record(SourceSystem.CRM, key, cp.getReference(),
                "ingested CRM profile (%s / %s) from %s".formatted(
                        String.valueOf(canonical.accountName()), String.valueOf(canonical.segment()),
                        envelope.vendor()));
        audit.human(actor, "CRM_INGESTED", "Counterparty", cp.getReference(),
                "Ingested CRM profile from vendor %s (advisory INPUT — no authoritative figure moved)"
                        .formatted(envelope.vendor()),
                Map.of("vendor", String.valueOf(envelope.vendor()),
                        "crmId", String.valueOf(canonical.crmId()),
                        "segment", String.valueOf(canonical.segment()),
                        "relationshipManager", String.valueOf(canonical.relationshipManager()),
                        "idempotencyKey", key, "advisory", true, "warnings", warnings));

        return Result.accepted(SourceSystem.CRM, key, cp.getReference(),
                "ingested CRM profile (advisory input)", warnings);
    }

    /** PULL: Helix fetches from the source (simulated by default; live via env base-url). */
    @Transactional
    public Result pull(Long counterpartyId, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        Envelope<RawCrmPayload> envelope = fetchSource.fetch(cp.getLegalName(), cp.getReference());
        return ingest(counterpartyId, envelope, actor);
    }

    @Transactional(readOnly = true)
    public CrmProfile latest(Long counterpartyId) {
        return profiles.findFirstByCounterpartyIdOrderByIdDesc(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No CRM profile for counterparty " + counterpartyId));
    }
}
