package com.helix.decision.repo;

import com.helix.decision.entity.Disbursement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long> {
    List<Disbursement> findByApplicationReferenceOrderByIdDesc(String applicationReference);
    List<Disbursement> findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(
            String applicationReference, String facilityRef);
    Optional<Disbursement> findFirstByApplicationReferenceAndFacilityRefOrderByDrawdownNoDesc(
            String applicationReference, String facilityRef);
}
