package com.helix.decision.repo;

import com.helix.decision.entity.SrmReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SrmReviewRepository extends JpaRepository<SrmReview, Long> {

    Optional<SrmReview> findBySrmRef(String srmRef);

    boolean existsBySrmRef(String srmRef);

    List<SrmReview> findAllByOrderByCreatedAtDesc();

    List<SrmReview> findBySubjectRefOrderByCreatedAtDesc(String subjectRef);

    Optional<SrmReview> findByNotingRef(String notingRef);
}
