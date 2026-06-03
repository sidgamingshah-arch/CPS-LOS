package com.helix.common.ingest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IngestionRecordRepository extends JpaRepository<IngestionRecord, Long> {
    Optional<IngestionRecord> findBySourceAndIdempotencyKey(String source, String idempotencyKey);
}
