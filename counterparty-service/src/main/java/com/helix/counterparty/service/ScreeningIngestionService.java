package com.helix.counterparty.service;

import com.helix.common.audit.AuditService;
import com.helix.common.ingest.Canonical;
import com.helix.common.ingest.Ingestion.Envelope;
import com.helix.common.ingest.Ingestion.Provenance;
import com.helix.common.ingest.Ingestion.Result;
import com.helix.common.ingest.IngestionGuard;
import com.helix.common.ingest.SourceSystem;
import com.helix.common.model.Enums.ScreeningDisposition;
import com.helix.common.web.ApiException;
import com.helix.counterparty.dto.IngestDtos.RawScreeningPayload;
import com.helix.counterparty.entity.Counterparty;
import com.helix.counterparty.entity.ScreeningHit;
import com.helix.counterparty.repo.CounterpartyRepository;
import com.helix.counterparty.repo.ScreeningHitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Ingests a sanctions/screening vendor feed via the canonical connector contract
 * (PRD §8): idempotent, provenance-stamped, failures surfaced as warnings. This is
 * the real ingestion path that the simulated {@code /screening/run} stands in for.
 */
@Service
public class ScreeningIngestionService {

    private final ScreeningConnector connector;
    private final CounterpartyRepository counterparties;
    private final ScreeningHitRepository hits;
    private final IngestionGuard guard;
    private final AuditService audit;

    public ScreeningIngestionService(ScreeningConnector connector, CounterpartyRepository counterparties,
                                     ScreeningHitRepository hits, IngestionGuard guard, AuditService audit) {
        this.connector = connector;
        this.counterparties = counterparties;
        this.hits = hits;
        this.guard = guard;
        this.audit = audit;
    }

    @Transactional
    public Result ingest(Long counterpartyId, Envelope<RawScreeningPayload> envelope, String actor) {
        Counterparty cp = counterparties.findById(counterpartyId)
                .orElseThrow(() -> ApiException.notFound("No counterparty: " + counterpartyId));
        String key = envelope.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }

        var prior = guard.priorIngestion(SourceSystem.SANCTIONS_SCREENING, key);
        if (prior.isPresent()) {
            return Result.duplicate(SourceSystem.SANCTIONS_SCREENING, key, prior.get().getCanonicalRef());
        }

        var warnings = connector.validate(envelope.payload());
        Provenance prov = Provenance.of(SourceSystem.SANCTIONS_SCREENING,
                envelope.vendor(), key, envelope.payloadVersion());
        Canonical.ScreeningResult canonical = connector.map(envelope.payload(), prov);

        int persisted = 0;
        for (Canonical.ScreeningResult.Hit h : canonical.hits()) {
            ScreeningHit hit = new ScreeningHit();
            hit.setCounterpartyId(cp.getId());
            hit.setListSource(h.listSource());
            hit.setMatchedName(h.matchedName());
            hit.setMatchScore(h.matchScore());
            hit.setSeverity(h.severity());
            hit.setMatchedAttributes(h.matchedAttributes());
            // Provenance note from the real inbound vendor feed (not fabricated AI text).
            hit.setAiRationale("Ingested from %s (%s); cites matched fields %s. Disposition is a named human action."
                    .formatted(envelope.vendor(), prov.sourceReference(), h.matchedAttributes()));
            hit.setRationaleSource("EXTERNAL");
            hit.setDisposition(ScreeningDisposition.OPEN.name());
            hits.save(hit);
            persisted++;
        }

        guard.record(SourceSystem.SANCTIONS_SCREENING, key, cp.getReference(),
                "ingested %d screening hit(s) from %s".formatted(persisted, envelope.vendor()));
        audit.human(actor, "SCREENING_INGESTED", "Counterparty", cp.getReference(),
                "Ingested %d screening hit(s) from vendor %s".formatted(persisted, envelope.vendor()),
                Map.of("vendor", String.valueOf(envelope.vendor()), "hits", persisted,
                        "idempotencyKey", key, "warnings", warnings));

        return Result.accepted(SourceSystem.SANCTIONS_SCREENING, key, cp.getReference(),
                "ingested %d screening hit(s)".formatted(persisted), warnings);
    }
}
