package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.common.ingest.IngestionGuard;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.IngestDtos.RawBureauPayload;
import com.helix.counterparty.entity.BureauRecord;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.repo.BureauRecordRepository;
import com.helix.counterparty.repo.CounterpartyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Ingests a credit-bureau feed via the canonical connector contract (PRD §8): idempotent,
 * provenance-stamped, failures surfaced as warnings — mirroring {@link ScreeningIngestionService}.
 *
 * <p><b>Governance:</b> the ingested bureau score is stored purely as INPUT / provenance data on
 * an advisory {@link BureauRecord}. It does NOT create or mutate a Rating, PD/LGD, capital, ECL or
 * any authoritative figure — the counterparty row is never touched here.
 */
@Service
public class BureauIngestionService {

    private final BureauConnector connector;
    private final CounterpartyRepository counterparties;
    private final BureauRecordRepository records;
    private final BureauFetchSource fetchSource;
    private final IngestionGuard guard;
    private final AuditService audit;

    public BureauIngestionService(BureauConnector connector, CounterpartyRepository counterparties,
                                  BureauRecordRepository records, BureauFetchSource fetchSource,
                                  IngestionGuard guard, AuditService audit) {
        this.connector = connector;
        this.counterparties = counterparties;
        this.records = records;
        this.fetchSource = fetchSource;
        this.guard = guard;
        this.audit = audit;
    }

    /** PUSH: an external caller POSTs a raw vendor payload. */
    @Transactional
    public Result ingest(Long counterpartyId, Envelope<RawBureauPayload> envelope, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        String key = envelope.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }

        var prior = guard.priorIngestion(SourceSystem.CREDIT_BUREAU, key);
        if (prior.isPresent()) {
            return Result.duplicate(SourceSystem.CREDIT_BUREAU, key, prior.get().getCanonicalRef());
        }

        var warnings = connector.validate(envelope.payload());
        Provenance prov = Provenance.of(SourceSystem.CREDIT_BUREAU,
                envelope.vendor(), key, envelope.payloadVersion());
        Canonical.BureauReport canonical = connector.map(envelope.payload(), prov);

        BureauRecord rec = new BureauRecord();
        rec.setCounterpartyId(cp.getId());
        rec.setSubjectName(canonical.subjectName());
        rec.setSubjectIdentifier(canonical.identifier());
        rec.setCreditScore(canonical.creditScore());
        rec.setScoreModel(canonical.scoreModel());
        rec.setInquiriesLast6m(canonical.inquiriesLast6m());
        rec.setDelinquenciesLast24m(canonical.delinquenciesLast24m());
        rec.setOpenTradelines(canonical.openTradelines());
        rec.setTotalOutstanding(canonical.totalOutstanding());
        rec.setOldestAccountMonths(canonical.oldestAccountMonths());
        rec.setSourceSystem(prov.sourceSystem().name());
        rec.setSourceVendor(prov.vendor());
        rec.setSourceReference(prov.sourceReference());
        rec.setPayloadVersion(prov.payloadVersion());
        rec.setRetrievedAt(prov.retrievedAt());
        rec.setAdvisory(true);
        records.save(rec);

        guard.record(SourceSystem.CREDIT_BUREAU, key, cp.getReference(),
                "ingested bureau report (score %s) from %s".formatted(
                        String.valueOf(canonical.creditScore()), envelope.vendor()));
        audit.human(actor, "BUREAU_INGESTED", "Counterparty", cp.getReference(),
                "Ingested bureau report from vendor %s (advisory INPUT — no authoritative figure moved)"
                        .formatted(envelope.vendor()),
                Map.of("vendor", String.valueOf(envelope.vendor()),
                        "creditScore", String.valueOf(canonical.creditScore()),
                        "scoreModel", String.valueOf(canonical.scoreModel()),
                        "idempotencyKey", key, "advisory", true, "warnings", warnings));

        return Result.accepted(SourceSystem.CREDIT_BUREAU, key, cp.getReference(),
                "ingested bureau report (advisory input)", warnings);
    }

    /** PULL: Helix fetches from the source (simulated by default; live via env base-url). */
    @Transactional
    public Result pull(Long counterpartyId, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        Envelope<RawBureauPayload> envelope = fetchSource.fetch(cp.getLegalName(), cp.getReference());
        return ingest(counterpartyId, envelope, actor);
    }

    @Transactional(readOnly = true)
    public BureauRecord latest(Long counterpartyId) {
        return records.findFirstByCounterpartyIdOrderByIdDesc(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No bureau report for counterparty " + counterpartyId));
    }
}
