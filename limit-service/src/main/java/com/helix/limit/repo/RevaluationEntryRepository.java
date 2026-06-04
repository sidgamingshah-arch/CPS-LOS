package com.helix.limit.repo;

import com.helix.limit.entity.RevaluationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RevaluationEntryRepository extends JpaRepository<RevaluationEntry, Long> {
    List<RevaluationEntry> findByRunIdOrderByIdAsc(Long runId);
}
