package com.helix.portfolio.repo;

import com.helix.portfolio.entity.DrawingPowerAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DrawingPowerAssessmentRepository extends JpaRepository<DrawingPowerAssessment, Long> {
    List<DrawingPowerAssessment> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    List<DrawingPowerAssessment> findByApplicationReferenceAndFacilityRefOrderByIdDesc(
            String applicationReference, String facilityRef);
}
