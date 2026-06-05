package com.helix.risk.repo;

import com.helix.risk.entity.PricingResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PricingResultRepository extends JpaRepository<PricingResult, Long> {
    Optional<PricingResult> findFirstByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);
}
