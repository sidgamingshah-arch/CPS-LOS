package com.helix.decision.repo;

import com.helix.decision.entity.PfMilestone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PfMilestoneRepository extends JpaRepository<PfMilestone, Long> {
    List<PfMilestone> findByApplicationReferenceAndFacilityRefOrderBySequenceAsc(String applicationReference, String facilityRef);
    List<PfMilestone> findByApplicationReferenceOrderBySequenceAsc(String applicationReference);
    Optional<PfMilestone> findByApplicationReferenceAndFacilityRefAndSequence(String applicationReference, String facilityRef, int sequence);
}
