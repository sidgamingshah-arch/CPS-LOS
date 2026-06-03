package com.helix.decision.repo;

import com.helix.decision.entity.CreditDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditDecisionRepository extends JpaRepository<CreditDecision, Long> {
    Optional<CreditDecision> findFirstByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);

    List<CreditDecision> findByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);
}
