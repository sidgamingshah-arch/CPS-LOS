package com.helix.portfolio.repo;

import com.helix.portfolio.entity.EclResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EclResultRepository extends JpaRepository<EclResult, Long> {
    Optional<EclResult> findFirstByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);
}
