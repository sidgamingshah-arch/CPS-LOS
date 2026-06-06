package com.helix.portfolio.repo;

import com.helix.portfolio.entity.ExportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExportBatchRepository extends JpaRepository<ExportBatch, Long> {
    Optional<ExportBatch> findByIdempotencyKey(String idempotencyKey);

    List<ExportBatch> findAllByOrderByIdDesc();

    List<ExportBatch> findByDestinationOrderByIdDesc(String destination);
}
