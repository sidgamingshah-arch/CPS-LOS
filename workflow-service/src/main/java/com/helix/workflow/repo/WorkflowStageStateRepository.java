package com.helix.workflow.repo;

import com.helix.workflow.entity.WorkflowStageState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowStageStateRepository extends JpaRepository<WorkflowStageState, Long> {
    List<WorkflowStageState> findByInstanceIdOrderByOrdinalAsc(Long instanceId);

    Optional<WorkflowStageState> findByInstanceIdAndStageKey(Long instanceId, String stageKey);

    List<WorkflowStageState> findBySlaDueAtBeforeAndStatusIn(Instant cutoff, List<String> statuses);

    List<WorkflowStageState> findByStatus(String status);
}
