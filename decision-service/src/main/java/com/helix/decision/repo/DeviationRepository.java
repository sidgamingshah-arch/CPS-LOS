package com.helix.decision.repo;

import com.helix.decision.entity.Deviation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviationRepository extends JpaRepository<Deviation, Long> {
    List<Deviation> findByCadCaseIdOrderByCreatedAtDesc(Long cadCaseId);
}
