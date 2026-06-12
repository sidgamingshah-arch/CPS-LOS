package com.helix.decision.repo;

import com.helix.decision.entity.CollectionsCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CollectionsCaseRepository extends JpaRepository<CollectionsCase, Long> {
    List<CollectionsCase> findAllByOrderByIdDesc();

    List<CollectionsCase> findByApplicationReferenceOrderByIdDesc(String applicationReference);

    Optional<CollectionsCase> findFirstByApplicationReferenceAndFacilityRefAndStatusIn(
            String applicationReference, String facilityRef, List<String> statuses);

    List<CollectionsCase> findByStatusOrderByIdDesc(String status);
}
