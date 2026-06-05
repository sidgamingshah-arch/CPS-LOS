package com.helix.risk.repo;

import com.helix.risk.entity.RagAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagAssessmentRepository extends JpaRepository<RagAssessment, Long> {
    List<RagAssessment> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    Optional<RagAssessment> findFirstByApplicationReferenceOrderByIdDesc(String applicationReference);
}
