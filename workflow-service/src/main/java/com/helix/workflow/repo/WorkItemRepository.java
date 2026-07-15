package com.helix.workflow.repo;

import com.helix.workflow.entity.WorkItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkItemRepository extends JpaRepository<WorkItem, Long> {

    Optional<WorkItem> findByTaskRef(String taskRef);

    boolean existsByTaskRef(String taskRef);

    /** Idempotency lookup — same logical task returns the existing row. */
    Optional<WorkItem> findFirstBySubjectTypeAndSubjectRefAndTaskTypeAndDedupeKey(
            String subjectType, String subjectRef, String taskType, String dedupeKey);

    List<WorkItem> findByAssigneeIgnoreCaseAndStatusInOrderByPriorityAscIdAsc(
            String assignee, List<String> statuses);

    List<WorkItem> findByQueueKeyAndStatusOrderByPriorityAscIdAsc(String queueKey, String status);

    List<WorkItem> findBySubjectTypeAndSubjectRefOrderByIdDesc(String subjectType, String subjectRef);

    List<WorkItem> findBySubjectRefOrderByIdAsc(String subjectRef);

    List<WorkItem> findByJoinGroupIdOrderByIdAsc(String joinGroupId);

    long countByAssigneeIgnoreCaseAndStatusIn(String assignee, List<String> statuses);
}
