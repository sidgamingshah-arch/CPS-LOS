package com.helix.decision.repo;

import com.helix.decision.entity.ConditionPrecedent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConditionPrecedentRepository extends JpaRepository<ConditionPrecedent, Long> {
    List<ConditionPrecedent> findByApplicationReferenceOrderByIdAsc(String applicationReference);
    List<ConditionPrecedent> findByApplicationReferenceAndFacilityRefOrderByIdAsc(String applicationReference, String facilityRef);
    List<ConditionPrecedent> findByApplicationReferenceAndFacilityRefAndStatusOrderByIdAsc(
            String applicationReference, String facilityRef, String status);
}
