package com.helix.decision.repo;

import com.helix.decision.entity.Noting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotingRepository extends JpaRepository<Noting, Long> {

    Optional<Noting> findByNotingRef(String notingRef);

    boolean existsByNotingRef(String notingRef);

    List<Noting> findAllByOrderByCreatedAtDesc();

    List<Noting> findBySubjectRefOrderByCreatedAtDesc(String subjectRef);

    List<Noting> findByStatusOrderByCreatedAtDesc(Noting.Status status);

    List<Noting> findByNotingTypeOrderByCreatedAtDesc(String notingType);
}
