package com.helix.risk.repo;

import com.helix.risk.entity.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RatingRepository extends JpaRepository<Rating, Long> {
    Optional<Rating> findFirstByApplicationReferenceOrderByCreatedAtDesc(String applicationReference);

    List<Rating> findBySegment(String segment);
}
