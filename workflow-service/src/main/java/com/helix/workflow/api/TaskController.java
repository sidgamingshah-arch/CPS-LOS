package com.helix.workflow.api;

import com.helix.workflow.dto.TaskDtos.AssignRequest;
import com.helix.workflow.dto.TaskDtos.CompleteResult;
import com.helix.workflow.dto.TaskDtos.CreateTaskRequest;
import com.helix.workflow.dto.TaskDtos.FanoutRequest;
import com.helix.workflow.dto.TaskDtos.FanoutResult;
import com.helix.workflow.dto.TaskDtos.NoteRequest;
import com.helix.workflow.dto.TaskDtos.SendBackResult;
import com.helix.workflow.entity.WorkItem;
import com.helix.workflow.entity.WorkItemEvent;
import com.helix.workflow.service.WorkItemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Case-management (task) surface. Gateway routes {@code /workflow/**} → here via
 * StripPrefix=1, so the public path is {@code /workflow/api/tasks/...}. Every write
 * takes {@code X-Actor}; the service stamps audit + appends a WorkItemEvent.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final WorkItemService tasks;

    public TaskController(WorkItemService tasks) {
        this.tasks = tasks;
    }

    @PostMapping
    public WorkItem create(@RequestBody CreateTaskRequest body,
                           @RequestHeader(value = "X-Actor", required = false) String actor) {
        // Derive actorType server-side — a request body cannot forge SYSTEM/AI over the
        // public HTTP boundary (the trusted in-process stage-entry mirror keeps its own).
        return tasks.create(withDerivedActorType(body, actor), actor);
    }

    @PostMapping("/fanout")
    public FanoutResult fanout(@RequestBody FanoutRequest body,
                               @RequestHeader(value = "X-Actor", required = false) String actor) {
        return tasks.fanout(withDerivedActorType(body, actor), actor);
    }

    @GetMapping("/inbox")
    public List<WorkItem> inbox(@RequestParam("assignee") String assignee,
                                @RequestParam(value = "scope", required = false) String scope,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        // No scope (or scope=self) is byte-identical to the pre-U9 inbox; scope=team folds in the
        // caller's subordinates resolved from USER_HIERARCHY (best-effort, fail-open to self-only).
        return tasks.inbox(assignee, actor, scope);
    }

    @GetMapping("/queue/{key}")
    public List<WorkItem> queue(@PathVariable("key") String queueKey,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        return tasks.queue(queueKey, actor);
    }

    /** Server-derived actorType: SYSTEM for a system principal, HUMAN otherwise (never trust the body). */
    private static String derivedActorType(String actor) {
        return (actor == null || actor.isBlank()
                || "system".equalsIgnoreCase(actor) || "engine".equalsIgnoreCase(actor))
                ? "SYSTEM" : "HUMAN";
    }

    private static CreateTaskRequest withDerivedActorType(CreateTaskRequest b, String actor) {
        if (b == null) return null;
        return new CreateTaskRequest(b.subjectType(), b.subjectRef(), b.taskType(), b.queueKey(),
                b.assignee(), b.priority(), b.slaHours(), b.dedupeKey(), derivedActorType(actor), b.payload());
    }

    private static FanoutRequest withDerivedActorType(FanoutRequest b, String actor) {
        if (b == null) return null;
        return new FanoutRequest(b.subjectType(), b.subjectRef(), b.taskType(),
                b.joinPolicy(), derivedActorType(actor), b.members());
    }

    @GetMapping("/subject")
    public List<WorkItem> subject(@RequestParam(value = "type", required = false) String type,
                                  @RequestParam("ref") String ref) {
        return tasks.forSubject(type, ref);
    }

    @GetMapping("/tat")
    public Map<String, Object> tat(@RequestParam("subjectRef") String subjectRef) {
        return tasks.tat(subjectRef);
    }

    /**
     * Deterministic TAT / MIS aggregations across the case book — cycle time, SLA, rework and
     * throughput, optionally scoped by {@code queueKey} / {@code taskType} and a created-at
     * window ({@code from} / {@code to} as ISO-8601 instants). Read-only report surface.
     */
    @GetMapping("/mis")
    public Map<String, Object> mis(@RequestParam(value = "queueKey", required = false) String queueKey,
                                   @RequestParam(value = "taskType", required = false) String taskType,
                                   @RequestParam(value = "from", required = false) String from,
                                   @RequestParam(value = "to", required = false) String to) {
        return tasks.mis(queueKey, taskType, from, to);
    }

    @GetMapping("/{ref}")
    public WorkItem view(@PathVariable("ref") String taskRef) {
        return tasks.require(taskRef);
    }

    @GetMapping("/{ref}/timeline")
    public List<WorkItemEvent> timeline(@PathVariable("ref") String taskRef) {
        return tasks.timeline(taskRef);
    }

    @PostMapping("/{ref}/claim")
    public WorkItem claim(@PathVariable("ref") String taskRef,
                          @RequestHeader(value = "X-Actor", required = false) String actor) {
        return tasks.claim(taskRef, actor);
    }

    @PostMapping("/{ref}/assign")
    public WorkItem assign(@PathVariable("ref") String taskRef,
                           @RequestBody AssignRequest body,
                           @RequestHeader(value = "X-Actor", required = false) String actor) {
        String reason = body == null ? null : body.reason();
        String assignee = body == null ? null : body.assignee();
        return tasks.assign(taskRef, assignee, reason, actor);
    }

    @PostMapping("/{ref}/complete")
    public CompleteResult complete(@PathVariable("ref") String taskRef,
                                   @RequestBody(required = false) NoteRequest body,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        String note = body == null ? null : body.note();
        return tasks.complete(taskRef, note, actor);
    }

    @PostMapping("/{ref}/send-back")
    public SendBackResult sendBack(@PathVariable("ref") String taskRef,
                                   @RequestBody(required = false) NoteRequest body,
                                   @RequestHeader(value = "X-Actor", required = false) String actor) {
        String note = body == null ? null : body.note();
        return tasks.sendBack(taskRef, note, actor);
    }

    @PostMapping("/{ref}/withdraw")
    public WorkItem withdraw(@PathVariable("ref") String taskRef,
                             @RequestBody(required = false) NoteRequest body,
                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        String note = body == null ? null : body.note();
        return tasks.withdraw(taskRef, note, actor);
    }
}
