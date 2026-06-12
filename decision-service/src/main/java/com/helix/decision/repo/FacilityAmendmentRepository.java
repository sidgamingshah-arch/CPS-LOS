package com.helix.decision.repo;

import com.helix.decision.entity.FacilityAmendment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacilityAmendmentRepository extends JpaRepository<FacilityAmendment, Long> {
    List<FacilityAmendment> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    List<FacilityAmendment> findByApplicationReferenceAndFacilityRefAndStatus(
            String applicationReference, String facilityRef, String status);
}
