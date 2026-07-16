package com.helix.workflow.repo;

import com.helix.workflow.entity.WorkItemEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkItemEventRepository extends JpaRepository<WorkItemEvent, Long> {

    List<WorkItemEvent> findByWorkItemRefOrderByIdAsc(String workItemRef);

    List<WorkItemEvent> findByWorkItemRefAndEventOrderByIdAsc(String workItemRef, String event);
}
