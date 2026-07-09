package com.helix.workflow.repo;

import com.helix.workflow.entity.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, Long> {
    Optional<WorkflowInstance> findByApplicationReference(String applicationReference);

    List<WorkflowInstance> findByStatusOrderByIdDesc(String status);

    List<WorkflowInstance> findBySlaBreachedTrue();
}
