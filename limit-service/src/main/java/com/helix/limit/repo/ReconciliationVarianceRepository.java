package com.helix.limit.repo;

import com.helix.limit.entity.ReconciliationVariance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationVarianceRepository extends JpaRepository<ReconciliationVariance, Long> {
    List<ReconciliationVariance> findByRunIdOrderByIdAsc(Long runId);
}
