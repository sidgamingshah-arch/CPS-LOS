package com.helix.risk.repo;

import com.helix.risk.entity.MacroImpactAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MacroImpactAssessmentRepository extends JpaRepository<MacroImpactAssessment, Long> {
    List<MacroImpactAssessment> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
