package com.helix.portfolio.repo;

import com.helix.portfolio.entity.RarocTracking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RarocTrackingRepository extends JpaRepository<RarocTracking, Long> {

    Optional<RarocTracking> findFirstByApplicationReferenceAndOriginationTrueOrderByComputedAtAsc(String reference);

    List<RarocTracking> findByApplicationReferenceOrderByComputedAtAsc(String reference);

    Optional<RarocTracking> findFirstByApplicationReferenceAndOriginationFalseOrderByComputedAtDesc(String reference);
}
