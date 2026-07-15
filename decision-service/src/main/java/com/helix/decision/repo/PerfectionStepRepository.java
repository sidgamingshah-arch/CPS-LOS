package com.helix.decision.repo;

import com.helix.decision.entity.PerfectionStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PerfectionStepRepository extends JpaRepository<PerfectionStep, Long> {

    List<PerfectionStep> findByCaseRefOrderByStepOrderAsc(String caseRef);

    Optional<PerfectionStep> findByCaseRefAndStepKey(String caseRef, String stepKey);
}
