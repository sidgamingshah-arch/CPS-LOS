package com.helix.portfolio.repo;

import com.helix.portfolio.entity.Tickler;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TicklerRepository extends JpaRepository<Tickler, Long> {

    Optional<Tickler> findByTicklerRef(String ticklerRef);

    List<Tickler> findByOrderByCreatedAtDesc();

    List<Tickler> findByStatusOrderByCreatedAtDesc(String status);

    List<Tickler> findBySubjectRefOrderByCreatedAtDesc(String subjectRef);
}
