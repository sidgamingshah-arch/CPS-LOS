package com.helix.workflow.repo;

import com.helix.workflow.entity.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, Long> {
    List<WorkflowTransition> findByInstanceIdOrderByIdAsc(Long instanceId);
}
