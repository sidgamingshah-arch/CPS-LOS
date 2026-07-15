package com.helix.workflow.dto;

import com.helix.workflow.entity.WorkItem;

import java.util.List;
import java.util.Map;

/** Request/response shapes for the case-management (task) API. DTOs are records. */
public final class TaskDtos {

    private TaskDtos() {
    }

    /** Create a single task. {@code assignee} explicit overrides pool resolution. */
    public record CreateTaskRequest(String subjectType, String subjectRef, String taskType,
                                    String queueKey, String assignee, Integer priority,
                                    Integer slaHours, String dedupeKey, String actorType,
                                    Map<String, Object> payload) {
    }

    /** One sibling of a fan-out. */
    public record FanoutMember(String queueKey, String assignee, Integer priority,
                               Integer slaHours, Map<String, Object> payload) {
    }

    /** Create N sibling tasks under one join group. */
    public record FanoutRequest(String subjectType, String subjectRef, String taskType,
                                String joinPolicy, String actorType, List<FanoutMember> members) {
    }

    public record AssignRequest(String assignee, String reason) {
    }

    public record NoteRequest(String note) {
    }

    /** Result of completing a task, including join-group evaluation when applicable. */
    public record CompleteResult(WorkItem task, String joinGroupId, String joinPolicy,
                                 Integer completedCount, Integer totalCount, Boolean joinGroupSatisfied) {
    }

    /** Result of a send-back: the original (now SENT_BACK) and the freshly opened rework task. */
    public record SendBackResult(WorkItem original, WorkItem rework) {
    }

    /** Result of a fan-out. */
    public record FanoutResult(String joinGroupId, String joinPolicy, int total,
                               boolean satisfied, List<WorkItem> tasks) {
    }
}
