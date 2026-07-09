package com.helix.decision.repo;

import com.helix.decision.entity.Repayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepaymentRepository extends JpaRepository<Repayment, Long> {
    List<Repayment> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    List<Repayment> findByApplicationReferenceAndFacilityRefOrderByIdDesc(
            String applicationReference, String facilityRef);

    List<Repayment> findByApplicationReferenceAndFacilityRefAndStatusIn(
            String applicationReference, String facilityRef, List<String> statuses);
}
