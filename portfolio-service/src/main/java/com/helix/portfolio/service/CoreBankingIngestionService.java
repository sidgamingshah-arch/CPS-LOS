package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.common.ingest.IngestionGuard;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.web.ApiException;
import com.helix.portfolio.dto.IngestDtos.RawCoreBankingFeed;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.repo.ExposureRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ingests a core-banking facility/conduct feed (PRD §8/§10/§11): idempotent,
 * provenance-stamped, with round-trip reconciliation against the booked exposure —
 * mismatches are surfaced as warnings, never silently dropped.
 */
@Service
public class CoreBankingIngestionService {

    private final CoreBankingConnector connector;
    private final ExposureRecordRepository exposures;
    private final IngestionGuard guard;
    private final AuditService audit;

    public CoreBankingIngestionService(CoreBankingConnector connector, ExposureRecordRepository exposures,
                                       IngestionGuard guard, AuditService audit) {
        this.connector = connector;
        this.exposures = exposures;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional
    public Result ingest(String reference, Envelope<RawCoreBankingFeed> envelope, String actor) {
        ExposureRecord exp = exposures.findByApplicationReference(reference)
                .orElseThrow(() -> ApiException.notFound("No booked exposure for " + reference));
        String key = envelope.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }

        var prior = guard.priorIngestion(SourceSystem.CORE_BANKING, key);
        if (prior.isPresent()) {
            return Result.duplicate(SourceSystem.CORE_BANKING, key, prior.get().getCanonicalRef());
        }

        List<String> warnings = new ArrayList<>(connector.validate(envelope.payload()));
        Provenance prov = Provenance.of(SourceSystem.CORE_BANKING, envelope.vendor(), key, envelope.payloadVersion());
        Canonical.CoreBankingPosition pos = connector.map(envelope.payload(), prov);

        // Round-trip reconciliation: drawn balance vs the booked EAD.
        if (Math.abs(pos.drawn() - exp.getEad()) / Math.max(1.0, exp.getEad()) > 0.01) {
            warnings.add("drawn %.0f differs from booked EAD %.0f — reconcile".formatted(pos.drawn(), exp.getEad()));
        }

        exp.setDaysPastDue(pos.daysPastDue());
        if (pos.status() != null && !pos.status().isBlank()) {
            exp.setStatus(pos.status());
        }
        exposures.save(exp);

        guard.record(SourceSystem.CORE_BANKING, key, reference,
                "core-banking conduct: %d dpd, status %s".formatted(pos.daysPastDue(), pos.status()));
        audit.human(actor, "CORE_BANKING_INGESTED", "Application", reference,
                "Conduct update: %d dpd, status %s%s".formatted(pos.daysPastDue(), pos.status(),
                        warnings.isEmpty() ? "" : " (warnings surfaced)"),
                Map.of("daysPastDue", pos.daysPastDue(), "status", pos.status(),
                        "idempotencyKey", key, "warnings", warnings));

        return Result.accepted(SourceSystem.CORE_BANKING, key, reference,
                "exposure conduct updated (%d dpd)".formatted(pos.daysPastDue()), warnings);
    }
}
