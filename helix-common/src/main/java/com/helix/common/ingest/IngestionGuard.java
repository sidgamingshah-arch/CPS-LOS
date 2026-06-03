package com.helix.common.ingest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Shared idempotency guard for connector ingestion (PRD §8). */
@Service
public class IngestionGuard {

    private final IngestionRecordRepository repository;

    public IngestionGuard(IngestionRecordRepository repository) {
        this.repository = repository;
    }

    /** Returns a prior ingestion for this (source, key) if the payload was already applied. */
    @Transactional(readOnly = true)
    public Optional<IngestionRecord> priorIngestion(SourceSystem source, String idempotencyKey) {
        return repository.findBySourceAndIdempotencyKey(source.name(), idempotencyKey);
    }

    /** Records an accepted ingestion so future replays are recognised. Joins the caller's tx. */
    @Transactional
    public IngestionRecord record(SourceSystem source, String idempotencyKey, String canonicalRef, String summary) {
        IngestionRecord rec = new IngestionRecord();
        rec.setSource(source.name());
        rec.setIdempotencyKey(idempotencyKey);
        rec.setCanonicalRef(canonicalRef);
        rec.setSummary(summary);
        return repository.save(rec);
    }
}
