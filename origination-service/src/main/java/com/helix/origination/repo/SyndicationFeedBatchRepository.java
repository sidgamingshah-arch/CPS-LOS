package com.helix.origination.repo;

import com.helix.origination.entity.SyndicationFeedBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SyndicationFeedBatchRepository extends JpaRepository<SyndicationFeedBatch, Long> {
    Optional<SyndicationFeedBatch> findByIdempotencyKey(String idempotencyKey);

    List<SyndicationFeedBatch> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
