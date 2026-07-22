package com.helix.portfolio.repo;

import com.helix.portfolio.entity.GlobalCashflowAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GlobalCashflowAssessmentRepository extends JpaRepository<GlobalCashflowAssessment, Long> {

    Optional<GlobalCashflowAssessment> findByGcfRef(String gcfRef);

    List<GlobalCashflowAssessment> findByGroupReferenceOrderByIdDesc(String groupReference);

    List<GlobalCashflowAssessment> findAllByOrderByIdDesc();
}
