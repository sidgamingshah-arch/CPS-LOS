package com.helix.risk.repo;

import com.helix.risk.entity.QualitativeAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QualitativeAssessmentRepository extends JpaRepository<QualitativeAssessment, Long> {
    List<QualitativeAssessment> findByApplicationReferenceOrderByIdAsc(String applicationReference);

    List<QualitativeAssessment> findByApplicationReferenceAndStatus(String applicationReference, String status);
}
