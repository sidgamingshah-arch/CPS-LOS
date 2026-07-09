package com.helix.decision.repo;

import com.helix.decision.entity.CertificateAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CertificateAssessmentRepository extends JpaRepository<CertificateAssessment, Long> {
    List<CertificateAssessment> findByApplicationReferenceOrderByIdDesc(String applicationReference);
}
