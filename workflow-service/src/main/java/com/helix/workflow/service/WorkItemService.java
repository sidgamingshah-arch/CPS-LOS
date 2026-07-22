package com.helix.workflow.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.workflow.client.WorkflowMasterClient;
import com.helix.workflow.client.WorkflowMasterClient.MasterRecordDto;
import com.helix.workflow.dto.TaskDtos.CompleteResult;
import com.helix.workflow.dto.TaskDtos.CreateTaskRequest;
import com.helix.workflow.dto.TaskDtos.FanoutMember;
import com.helix.workflow.dto.TaskDtos.FanoutRequest;
import com.helix.workflow.dto.TaskDtos.FanoutResult;
import com.helix.workflow.dto.TaskDtos.SendBackResult;
import com.helix.workflow.entity.QueueCursor;
import com.helix.workflow.entity.WorkItem;
import com.helix.workflow.entity.WorkItemEvent;
import com.helix.workflow.repo.QueueCursorRepository;
import com.helix.workflow.repo.WorkItemEventRepository;
import com.helix.workflow.repo.WorkItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Case-management (task) layer. A task is a <b>mirror</b> over the authoritative
 * domain status machines: it records who must act and the TAT timeline, but it
 * never approves anything and holds no credit figures. Assignment is driven by the
 * {@code ASSIGNMENT_POOL} master (round-robin / least-loaded / manual), skipping
 * members who are out-of-office per the {@code OOO_CALENDAR} master.
 */
@Service
public class WorkItemService {

    private static final Logger log = LoggerFactory.getLogger(WorkItemService.class);

    private static final List<String> OPEN_STATES = List.of("OPEN", "ASSIGNED");
    private static final List<String> TERMINAL = List.of("COMPLETED", "WITHDRAWN", "CANCELLED", "SENT_BACK");

    private final WorkItemRepository items;
    private final WorkItemEventRepository events;
    private final QueueCursorRepository cursors;
    private final WorkflowMasterClient masters;
    private final AuditService audit;

    /** Optional — resolves an actor's directory roles for role-based pool gating. */
    private final ActorDirectory actorDirectory;

    public WorkItemService(WorkItemRepository items, WorkItemEventRepository events,
                           QueueCursorRepository cursors, WorkflowMasterClient masters,
                           AuditService audit,
                           @Autowired(required = false) ActorDirectory actorDirectory) {
        this.items = items;
        this.events = events;
        this.cursors = cursors;
        this.masters = masters;
        this.audit = audit;
        this.actorDirectory = actorDirectory;
    }

    // =============================================================== create

    @Transactional
    public WorkItem create(CreateTaskRequest req, String actor) {
        if (req == null || blank(req.subjectType()) || blank(req.subjectRef()) || blank(req.taskType())) {
            throw ApiException.badRequest("subjectType, subjectRef and taskType are required");
        }
        String dedupe = req.dedupeKey() == null ? "" : req.dedupeKey().trim();
        Optional<WorkItem> existing = items.findFirstBySubjectTypeAndSubjectRefAndTaskTypeAndDedupeKey(
                req.subjectType(), req.subjectRef(), req.taskType(), dedupe);
        if (existing.isPresent()) {
            // Idempotent — re-creating the same logical task returns the existing one.
            return existing.get();
        }

        WorkItem wi = new WorkItem();
        wi.setTaskRef(freshRef());
        wi.setSubjectType(req.subjectType());
        wi.setSubjectRef(req.subjectRef());
        wi.setTaskType(req.taskType());
        wi.setQueueKey(req.queueKey());
        wi.setDedupeKey(dedupe);
        wi.setPriority(req.priority() == null ? 5 : req.priority());
        wi.setCreatedBy(actor);
        wi.setPayload(req.payload() == null ? Map.of() : req.payload());

        PoolSpec pool = resolvePool(req.queueKey());

        // Resolve assignee: an explicit assignee wins; otherwise the pool strategy decides.
        String assignee = !blank(req.assignee()) ? req.assignee() : assignFromPool(req.queueKey(), pool);
        if (!blank(assignee)) {
            wi.setAssignee(assignee);
            wi.setStatus("ASSIGNED");
        } else {
            wi.setStatus("OPEN");
        }

        Integer sla = req.slaHours() != null ? req.slaHours() : pool.slaHours();
        if (sla != null && sla > 0) {
            wi.setSlaHours(sla);
            wi.setDueAt(Instant.now().plusSeconds(sla * 3600L));
        }

        WorkItem saved = items.save(wi);
        String actorType = normalizeActorType(req.actorType(), actor);
        appendEvent(saved.getTaskRef(), "CREATED", actor, actorType,
                "Task " + saved.getTaskType() + " opened for " + saved.getSubjectRef());
        if (!blank(assignee)) {
            appendEvent(saved.getTaskRef(), "ASSIGNED", actor, actorType, "Auto-assigned to " + assignee);
        }
        stampCreate(actor, actorType, saved);
        return saved;
    }

    // =============================================================== fan-out + join

    @Transactional
    public FanoutResult fanout(FanoutRequest req, String actor) {
        if (req == null || blank(req.subjectType()) || blank(req.subjectRef()) || blank(req.taskType())) {
            throw ApiException.badRequest("subjectType, subjectRef and taskType are required");
        }
        if (req.members() == null || req.members().isEmpty()) {
            throw ApiException.badRequest("fan-out requires at least one member");
        }
        String groupId = "JG-" + rand6();
        String policy = blank(req.joinPolicy()) ? "ALL" : req.joinPolicy().trim().toUpperCase(Locale.ROOT);
        String actorType = normalizeActorType(req.actorType(), actor);

        List<WorkItem> created = new ArrayList<>();
        int idx = 0;
        for (FanoutMember m : req.members()) {
            WorkItem wi = new WorkItem();
            wi.setTaskRef(freshRef());
            wi.setSubjectType(req.subjectType());
            wi.setSubjectRef(req.subjectRef());
            wi.setTaskType(req.taskType());
            wi.setQueueKey(m.queueKey());
            wi.setDedupeKey(groupId + ":" + idx);
            wi.setPriority(m.priority() == null ? 5 : m.priority());
            wi.setJoinGroupId(groupId);
            wi.setJoinPolicy(policy);
            wi.setCreatedBy(actor);
            wi.setPayload(m.payload() == null ? Map.of() : m.payload());

            PoolSpec pool = resolvePool(m.queueKey());
            String assignee = !blank(m.assignee()) ? m.assignee() : assignFromPool(m.queueKey(), pool);
            if (!blank(assignee)) {
                wi.setAssignee(assignee);
                wi.setStatus("ASSIGNED");
            } else {
                wi.setStatus("OPEN");
            }
            Integer sla = m.slaHours() != null ? m.slaHours() : pool.slaHours();
            if (sla != null && sla > 0) {
                wi.setSlaHours(sla);
                wi.setDueAt(Instant.now().plusSeconds(sla * 3600L));
            }
            WorkItem saved = items.save(wi);
            appendEvent(saved.getTaskRef(), "CREATED", actor, actorType,
                    "Fan-out sibling (" + policy + ") for " + saved.getSubjectRef());
            if (!blank(assignee)) {
                appendEvent(saved.getTaskRef(), "ASSIGNED", actor, actorType, "Auto-assigned to " + assignee);
            }
            created.add(saved);
            idx++;
        }
        audit.human(actor == null ? "system" : actor, "TASK_FANOUT", "WorkItemGroup", groupId,
                "Fanned out %d %s task(s) with join %s".formatted(created.size(), req.taskType(), policy),
                Map.of("joinGroupId", groupId, "joinPolicy", policy, "count", created.size(),
                        "subjectRef", req.subjectRef()));
        return new FanoutResult(groupId, policy, created.size(), false, created);
    }

    // =============================================================== claim / assign / complete

    @Transactional
    public WorkItem claim(String taskRef, String actor) {
        WorkItem wi = require(taskRef);
        if (!"OPEN".equals(wi.getStatus())) {
            throw ApiException.conflict("Task " + taskRef + " is not claimable (status " + wi.getStatus() + ")");
        }
        if (blank(actor)) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to claim a task");
        }
        PoolSpec pool = resolvePool(wi.getQueueKey());
        if (!isMember(pool, actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' is not a member of the '" + wi.getQueueKey()
                    + "' pool and cannot claim this task");
        }
        wi.setAssignee(actor);
        wi.setStatus("ASSIGNED");
        WorkItem saved = items.save(wi);
        appendEvent(taskRef, "CLAIMED", actor, "HUMAN", "Claimed from queue " + wi.getQueueKey());
        audit.human(actor, "TASK_CLAIMED", "WorkItem", taskRef,
                "Claimed " + wi.getTaskType() + " for " + wi.getSubjectRef(), Map.of("queueKey", str(wi.getQueueKey())));
        return saved;
    }

    @Transactional
    public WorkItem assign(String taskRef, String targetAssignee, String reason, String actor) {
        if (blank(reason)) {
            throw ApiException.badRequest("A reason is mandatory to reassign a task");
        }
        WorkItem wi = require(taskRef);
        if (TERMINAL.contains(wi.getStatus())) {
            throw ApiException.conflict("Cannot reassign a " + wi.getStatus() + " task");
        }
        PoolSpec pool = resolvePool(wi.getQueueKey());
        if (!isSupervisor(pool, actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' is not a supervisor of the '" + wi.getQueueKey()
                    + "' pool and cannot reassign this task");
        }
        wi.setAssignee(blank(targetAssignee) ? null : targetAssignee);
        wi.setStatus(blank(targetAssignee) ? "OPEN" : "ASSIGNED");
        WorkItem saved = items.save(wi);
        appendEvent(taskRef, "REASSIGNED", actor, "HUMAN",
                "Reassigned to " + str(targetAssignee) + " — " + reason);
        audit.human(actor, "TASK_REASSIGNED", "WorkItem", taskRef,
                "Reassigned to %s (%s)".formatted(str(targetAssignee), reason),
                Map.of("assignee", str(targetAssignee), "reason", reason));
        return saved;
    }

    @Transactional
    public CompleteResult complete(String taskRef, String note, String actor) {
        // The task ref may be a random TSK- ref (direct completion) OR a dedupeKey
        // (the TaskClient mirror-complete path — callers pass e.g. "NTG:<ref>"). Resolve
        // the direct ref first, then fall back to the newest still-open WorkItem for that key.
        Optional<WorkItem> direct = items.findByTaskRef(taskRef);
        boolean resolvedByDedupe = direct.isEmpty();
        WorkItem wi = direct.orElseGet(() ->
                items.findFirstByDedupeKeyAndStatusInOrderByIdDesc(taskRef, OPEN_STATES)
                        .orElseThrow(() -> ApiException.notFound("No task: " + taskRef)));
        String ref = wi.getTaskRef();
        if (TERMINAL.contains(wi.getStatus())) {
            throw ApiException.conflict("Task " + ref + " is already " + wi.getStatus());
        }
        PoolSpec pool = resolvePool(wi.getQueueKey());
        boolean isAssignee = !blank(wi.getAssignee()) && wi.getAssignee().equalsIgnoreCase(actor);
        // A mirror task holds no human assignee; when it is resolved via its dedupeKey (the
        // originating SYSTEM/TaskClient mirror-complete path) closing it carries no ownership,
        // so a pool role is not required. A CLAIMED task keeps its assignee/supervisor gate.
        boolean systemMirror = resolvedByDedupe && blank(wi.getAssignee());
        if (!isAssignee && !isSupervisor(pool, actor) && !systemMirror) {
            throw ApiException.forbiddenAutonomy(
                    "Only the current assignee or a pool supervisor may complete task " + ref);
        }
        Instant now = Instant.now();
        if (wi.getDueAt() != null && now.isAfter(wi.getDueAt())) {
            wi.setSlaBreached(true);
        }
        wi.setStatus("COMPLETED");
        WorkItem saved = items.save(wi);
        appendEvent(ref, "COMPLETED", actor, normalizeActorType(null, actor), note);
        audit.human(actor == null ? "system" : actor, "TASK_COMPLETED", "WorkItem", ref,
                "Completed " + wi.getTaskType() + " for " + wi.getSubjectRef(),
                Map.of("subjectRef", str(wi.getSubjectRef()), "slaBreached", wi.isSlaBreached()));

        // Join-group evaluation.
        if (!blank(wi.getJoinGroupId())) {
            List<WorkItem> siblings = items.findByJoinGroupIdOrderByIdAsc(wi.getJoinGroupId());
            int completed = (int) siblings.stream().filter(s -> "COMPLETED".equals(s.getStatus())).count();
            int total = siblings.size();
            boolean satisfied = JoinPolicy.satisfied(wi.getJoinPolicy(), completed, total);
            return new CompleteResult(saved, wi.getJoinGroupId(), wi.getJoinPolicy(), completed, total, satisfied);
        }
        return new CompleteResult(saved, null, null, null, null, null);
    }

    // =============================================================== send-back (rework)

    @Transactional
    public SendBackResult sendBack(String taskRef, String note, String actor) {
        if (blank(actor)) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to send a task back");
        }
        WorkItem wi = require(taskRef);
        if (TERMINAL.contains(wi.getStatus())) {
            throw ApiException.conflict("Cannot send back a " + wi.getStatus() + " task");
        }
        // Authorize like complete(): only the current assignee or a pool supervisor may send back.
        PoolSpec pool = resolvePool(wi.getQueueKey());
        boolean isAssignee = !blank(wi.getAssignee()) && wi.getAssignee().equalsIgnoreCase(actor);
        if (!isAssignee && !isSupervisor(pool, actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Only the current assignee or a pool supervisor may send back task " + taskRef);
        }
        wi.setStatus("SENT_BACK");
        WorkItem original = items.save(wi);
        appendEvent(taskRef, "SENT_BACK", actor, normalizeActorType(null, actor), note);

        // Open a REWORK task back to the originator (or the queue if unknown), reworkCycle+1.
        int cycle = wi.getReworkCycle() + 1;
        String originator = originatorOf(taskRef);
        WorkItem rw = new WorkItem();
        rw.setTaskRef(freshRef());
        rw.setSubjectType(wi.getSubjectType());
        rw.setSubjectRef(wi.getSubjectRef());
        rw.setTaskType(wi.getTaskType());
        rw.setQueueKey(wi.getQueueKey());
        rw.setDedupeKey((blank(wi.getDedupeKey()) ? "" : wi.getDedupeKey()) + ":rework:" + cycle);
        rw.setPriority(wi.getPriority());
        rw.setReworkCycle(cycle);
        rw.setCreatedBy(actor);
        Map<String, Object> p = new LinkedHashMap<>(wi.getPayload() == null ? Map.of() : wi.getPayload());
        p.put("rework", true);
        p.put("originTaskRef", wi.getTaskRef());
        p.put("reworkReason", note);
        rw.setPayload(p);
        if (!blank(originator)) {
            rw.setAssignee(originator);
            rw.setStatus("ASSIGNED");
        } else {
            rw.setStatus("OPEN");
        }
        WorkItem rework = items.save(rw);
        appendEvent(rework.getTaskRef(), "CREATED", actor, normalizeActorType(null, actor),
                "Rework cycle " + cycle + " from " + wi.getTaskRef());
        if (!blank(originator)) {
            appendEvent(rework.getTaskRef(), "ASSIGNED", actor, normalizeActorType(null, actor),
                    "Returned to originator " + originator);
        }
        audit.human(actor, "TASK_SENT_BACK", "WorkItem", taskRef,
                "Sent back %s; opened rework %s (cycle %d)".formatted(wi.getTaskType(), rework.getTaskRef(), cycle),
                Map.of("reworkTaskRef", rework.getTaskRef(), "reworkCycle", cycle, "reason", str(note)));
        return new SendBackResult(original, rework);
    }

    // =============================================================== withdraw

    @Transactional
    public WorkItem withdraw(String taskRef, String note, String actor) {
        if (blank(actor)) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to withdraw a task");
        }
        WorkItem wi = require(taskRef);
        if (TERMINAL.contains(wi.getStatus())) {
            throw ApiException.conflict("Task " + taskRef + " is already " + wi.getStatus());
        }
        // Authorize to the current assignee, the task creator, or a pool supervisor.
        PoolSpec pool = resolvePool(wi.getQueueKey());
        boolean isAssignee = !blank(wi.getAssignee()) && wi.getAssignee().equalsIgnoreCase(actor);
        boolean isCreator = !blank(wi.getCreatedBy()) && wi.getCreatedBy().equalsIgnoreCase(actor);
        if (!isAssignee && !isCreator && !isSupervisor(pool, actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Only the assignee, the task creator, or a pool supervisor may withdraw task " + taskRef);
        }
        wi.setStatus("WITHDRAWN");
        WorkItem saved = items.save(wi);
        appendEvent(taskRef, "WITHDRAWN", actor, normalizeActorType(null, actor), note);
        audit.human(actor, "TASK_WITHDRAWN", "WorkItem", taskRef,
                "Withdrew " + wi.getTaskType() + " for " + wi.getSubjectRef(), Map.of("reason", str(note)));
        return saved;
    }

    // =============================================================== reads

    @Transactional(readOnly = true)
    public WorkItem require(String taskRef) {
        return items.findByTaskRef(taskRef)
                .orElseThrow(() -> ApiException.notFound("No task: " + taskRef));
    }

    /**
     * Inbox read with an optional {@code scope}. {@code null} / blank / {@code self} is
     * byte-identical to {@link #inbox(String, String)} (the pre-U9 behaviour). {@code team}
     * additionally folds in the open work-items of the CALLER's subordinates resolved from the
     * {@code USER_HIERARCHY} master — best-effort and DEFAULT-PERMISSIVE: an unmapped supervisor
     * or a masters outage degrades to self-only. An unrecognised scope is treated as self.
     */
    @Transactional(readOnly = true)
    public List<WorkItem> inbox(String assignee, String actor, String scope) {
        if (!blank(scope) && "team".equalsIgnoreCase(scope.trim())) {
            return teamInbox(assignee, actor);
        }
        return inbox(assignee, actor);   // self / default — unchanged
    }

    /**
     * The caller's own inbox plus the open work-items of every actor who reports to the caller
     * (USER_HIERARCHY.supervisor == caller). The base list reuses {@link #inbox(String, String)}
     * so its authorization (named actor + own-or-supervisor) is unchanged; the team expansion is
     * rooted at the CALLER, so a supervisor only ever sees their own reports' tasks. Fail-open:
     * an empty / unavailable hierarchy leaves the result equal to the caller's own inbox.
     */
    private List<WorkItem> teamInbox(String assignee, String actor) {
        List<WorkItem> base = inbox(assignee, actor);
        List<String> subs;
        try {
            subs = masters.subordinatesOf(actor);
        } catch (Exception e) {
            log.warn("USER_HIERARCHY lookup failed for {} ({}) — team scope degrades to self-only",
                    actor, e.getMessage());
            return base;
        }
        if (subs == null || subs.isEmpty()) {
            return base;   // unmapped supervisor -> self-only (no regression)
        }
        LinkedHashMap<String, WorkItem> merged = new LinkedHashMap<>();
        for (WorkItem wi : base) {
            merged.put(wi.getTaskRef(), wi);
        }
        for (String sub : subs) {
            if (blank(sub) || sub.equalsIgnoreCase(assignee) || sub.equalsIgnoreCase(actor)) {
                continue;
            }
            for (WorkItem wi : items.findByAssigneeIgnoreCaseAndStatusInOrderByPriorityAscIdAsc(sub, OPEN_STATES)) {
                merged.putIfAbsent(wi.getTaskRef(), wi);
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Transactional(readOnly = true)
    public List<WorkItem> inbox(String assignee, String actor) {
        if (blank(assignee)) throw ApiException.badRequest("assignee is required");
        if (blank(actor)) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to read an inbox");
        }
        List<WorkItem> mine =
                items.findByAssigneeIgnoreCaseAndStatusInOrderByPriorityAscIdAsc(assignee, OPEN_STATES);
        if (actor.equalsIgnoreCase(assignee)) return mine;   // own inbox
        // Otherwise only a supervisor of a pool owning any of the assignee's open tasks may look.
        boolean supervises = mine.stream()
                .map(WorkItem::getQueueKey)
                .filter(q -> !blank(q))
                .distinct()
                .anyMatch(q -> isSupervisor(resolvePool(q), actor));
        if (!supervises) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' may not read the inbox of '" + assignee + "'");
        }
        return mine;
    }

    @Transactional(readOnly = true)
    public List<WorkItem> queue(String queueKey, String actor) {
        if (blank(actor)) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to read a queue");
        }
        PoolSpec pool = resolvePool(queueKey);
        if (!isMember(pool, actor) && !isSupervisor(pool, actor)) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' is not a member or supervisor of the '" + queueKey + "' pool");
        }
        return items.findByQueueKeyAndStatusOrderByPriorityAscIdAsc(queueKey, "OPEN");
    }

    @Transactional(readOnly = true)
    public List<WorkItem> forSubject(String subjectType, String subjectRef) {
        if (blank(subjectRef)) throw ApiException.badRequest("subjectRef is required");
        if (blank(subjectType)) {
            return items.findBySubjectRefOrderByIdAsc(subjectRef);
        }
        return items.findBySubjectTypeAndSubjectRefOrderByIdDesc(subjectType, subjectRef);
    }

    @Transactional(readOnly = true)
    public List<WorkItemEvent> timeline(String taskRef) {
        require(taskRef);
        return events.findByWorkItemRefOrderByIdAsc(taskRef);
    }

    /** TAT / SLA report derived from the event timeline for every task on a subject. */
    @Transactional(readOnly = true)
    public Map<String, Object> tat(String subjectRef) {
        if (blank(subjectRef)) throw ApiException.badRequest("subjectRef is required");
        List<WorkItem> subjectItems = items.findBySubjectRefOrderByIdAsc(subjectRef);
        List<Map<String, Object>> rows = new ArrayList<>();
        long totalMinutes = 0;
        int completed = 0;
        int breached = 0;
        int reworkTotal = 0;
        Instant now = Instant.now();
        for (WorkItem wi : subjectItems) {
            List<WorkItemEvent> tl = events.findByWorkItemRefOrderByIdAsc(wi.getTaskRef());
            Instant createdAt = tl.stream().filter(e -> "CREATED".equals(e.getEvent()))
                    .map(WorkItemEvent::getAt).findFirst().orElse(wi.getCreatedAt());
            Instant completedAt = tl.stream().filter(e -> "COMPLETED".equals(e.getEvent()))
                    .map(WorkItemEvent::getAt).reduce((a, b) -> b).orElse(null);
            boolean isBreached = wi.isSlaBreached()
                    || (wi.getDueAt() != null && completedAt == null && now.isAfter(wi.getDueAt()));
            Long minutes = null;
            if (createdAt != null && completedAt != null) {
                minutes = (completedAt.getEpochSecond() - createdAt.getEpochSecond()) / 60;
                totalMinutes += minutes;
                completed++;
            }
            if (isBreached) breached++;
            reworkTotal += wi.getReworkCycle();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskRef", wi.getTaskRef());
            row.put("taskType", wi.getTaskType());
            row.put("queueKey", wi.getQueueKey());
            row.put("assignee", wi.getAssignee());
            row.put("status", wi.getStatus());
            row.put("reworkCycle", wi.getReworkCycle());
            row.put("createdAt", createdAt);
            row.put("completedAt", completedAt);
            row.put("tatMinutes", minutes);
            row.put("slaHours", wi.getSlaHours());
            row.put("dueAt", wi.getDueAt());
            row.put("slaBreached", isBreached);
            row.put("events", tl.size());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subjectRef", subjectRef);
        out.put("taskCount", subjectItems.size());
        out.put("completedCount", completed);
        out.put("breachedCount", breached);
        out.put("reworkCycles", reworkTotal);
        out.put("avgTatMinutes", completed == 0 ? null : totalMinutes / completed);
        out.put("tasks", rows);
        return out;
    }

    // =============================================================== TAT / MIS aggregations

    /**
     * Deterministic TAT / MIS report over the whole case book (optionally scoped by
     * {@code queueKey} / {@code taskType} and a created-at window {@code [from,to]}).
     *
     * <p>This is a pure <b>read/report</b> surface: it computes cycle-time, SLA, rework and
     * throughput aggregations from the {@link WorkItem} rows and their {@link WorkItemEvent}
     * bookends — it never writes, never touches an authoritative domain figure, and involves no
     * AI. Cycle time is the span between the task's {@code CREATED} and {@code COMPLETED} events;
     * for a completed item these coincide with the row's {@code createdAt} and {@code updatedAt}
     * (the final save is the completion), so the derivation stays O(n) rather than N+1.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> mis(String queueKey, String taskType, String fromRaw, String toRaw) {
        Instant from = parseInstant(fromRaw);
        Instant to = parseInstant(toRaw);
        Instant now = Instant.now();

        List<WorkItem> scope = new ArrayList<>();
        for (WorkItem wi : items.findAll()) {
            if (!blank(queueKey) && !queueKey.equals(wi.getQueueKey())) continue;
            if (!blank(taskType) && !taskType.equals(wi.getTaskType())) continue;
            Instant created = wi.getCreatedAt();
            if (from != null && (created == null || created.isBefore(from))) continue;
            if (to != null && (created == null || created.isAfter(to))) continue;
            scope.add(wi);
        }

        // Group by queueKey (nulls → "UNQUEUED") and by taskType, in stable key order.
        Map<String, List<WorkItem>> byQueue = new TreeMap<>();
        Map<String, List<WorkItem>> byType = new TreeMap<>();
        Map<String, Integer> reworkDist = new TreeMap<>();
        Map<String, Long> assigneeLoad = new TreeMap<>();
        for (WorkItem wi : scope) {
            byQueue.computeIfAbsent(blank(wi.getQueueKey()) ? "UNQUEUED" : wi.getQueueKey(),
                    k -> new ArrayList<>()).add(wi);
            byType.computeIfAbsent(blank(wi.getTaskType()) ? "UNKNOWN" : wi.getTaskType(),
                    k -> new ArrayList<>()).add(wi);
            reworkDist.merge(String.valueOf(wi.getReworkCycle()), 1, Integer::sum);
            if (isOpen(wi) && !blank(wi.getAssignee())) {
                assigneeLoad.merge(wi.getAssignee(), 1L, Long::sum);
            }
        }

        Map<String, Object> totals = aggregate(scope, now, from, to);

        List<Map<String, Object>> queueRows = new ArrayList<>();
        for (Map.Entry<String, List<WorkItem>> e : byQueue.entrySet()) {
            Map<String, Object> row = aggregate(e.getValue(), now, from, to);
            row.put("queueKey", e.getKey());
            queueRows.add(row);
        }
        List<Map<String, Object>> typeRows = new ArrayList<>();
        for (Map.Entry<String, List<WorkItem>> e : byType.entrySet()) {
            Map<String, Object> row = aggregate(e.getValue(), now, from, to);
            row.put("taskType", e.getKey());
            typeRows.add(row);
        }
        List<Map<String, Object>> reworkRows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : reworkDist.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reworkCycle", Integer.parseInt(e.getKey()));
            row.put("count", e.getValue());
            reworkRows.add(row);
        }
        // Order the rework distribution numerically (TreeMap keys are strings, so "10" < "2").
        reworkRows.sort((a, b) -> Integer.compare((int) a.get("reworkCycle"), (int) b.get("reworkCycle")));
        List<Map<String, Object>> loadRows = new ArrayList<>();
        for (Map.Entry<String, Long> e : assigneeLoad.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assignee", e.getKey());
            row.put("openCount", e.getValue());
            loadRows.add(row);
        }
        loadRows.sort((a, b) -> Long.compare((long) b.get("openCount"), (long) a.get("openCount")));

        Map<String, Object> throughput = new LinkedHashMap<>();
        throughput.put("createdInWindow", totals.get("createdInWindow"));
        throughput.put("completedInWindow", totals.get("completedInWindow"));
        throughput.put("openBacklog", totals.get("openCount"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("queueKey", blank(queueKey) ? null : queueKey);
        filter.put("taskType", blank(taskType) ? null : taskType);
        filter.put("from", from);
        filter.put("to", to);
        out.put("filter", filter);
        out.put("totals", totals);
        out.put("byQueue", queueRows);
        out.put("byTaskType", typeRows);
        out.put("reworkDistribution", reworkRows);
        out.put("throughput", throughput);
        out.put("assigneeLoad", loadRows);
        return out;
    }

    /** Deterministic metric block for a set of work-items (reused for totals + each grouping row). */
    private Map<String, Object> aggregate(List<WorkItem> group, Instant now, Instant from, Instant to) {
        int count = group.size();
        int completed = 0, open = 0, sentBack = 0, withdrawn = 0, breached = 0, openOverdue = 0, reworkTasks = 0;
        List<Double> cycleHours = new ArrayList<>();
        int createdInWindow = 0, completedInWindow = 0;
        for (WorkItem wi : group) {
            String st = wi.getStatus();
            boolean isOpen = isOpen(wi);
            if ("COMPLETED".equals(st)) completed++;
            if (isOpen) open++;
            if ("SENT_BACK".equals(st)) sentBack++;
            if ("WITHDRAWN".equals(st) || "CANCELLED".equals(st)) withdrawn++;
            if (wi.getReworkCycle() > 0) reworkTasks++;

            boolean overdueOpen = wi.getDueAt() != null && isOpen && now.isAfter(wi.getDueAt());
            if (wi.isSlaBreached() || overdueOpen) breached++;
            if (overdueOpen) openOverdue++;

            if ("COMPLETED".equals(st) && wi.getCreatedAt() != null && wi.getUpdatedAt() != null) {
                double hours = (wi.getUpdatedAt().getEpochSecond() - wi.getCreatedAt().getEpochSecond()) / 3600.0;
                if (hours < 0) hours = 0;
                cycleHours.add(hours);
                if (inWindow(wi.getUpdatedAt(), from, to)) completedInWindow++;
            }
            if (inWindow(wi.getCreatedAt(), from, to)) createdInWindow++;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("completedCount", completed);
        m.put("openCount", open);
        m.put("sentBackCount", sentBack);
        m.put("withdrawnCount", withdrawn);
        m.put("reworkTaskCount", reworkTasks);
        m.put("breachedCount", breached);
        m.put("breachRate", count == 0 ? null : round((double) breached / count, 4));
        m.put("openOverdueCount", openOverdue);
        m.put("sendBackRate", count == 0 ? null : round((double) sentBack / count, 4));
        m.put("avgCycleHours", cycleHours.isEmpty() ? null
                : round(cycleHours.stream().mapToDouble(Double::doubleValue).sum() / cycleHours.size(), 4));
        m.put("medianCycleHours", median(cycleHours));
        m.put("maxCycleHours", cycleHours.isEmpty() ? null
                : round(cycleHours.stream().mapToDouble(Double::doubleValue).max().orElse(0), 4));
        m.put("createdInWindow", createdInWindow);
        m.put("completedInWindow", completedInWindow);
        return m;
    }

    private boolean isOpen(WorkItem wi) {
        return OPEN_STATES.contains(wi.getStatus());
    }

    private static boolean inWindow(Instant at, Instant from, Instant to) {
        if (at == null) return false;
        if (from != null && at.isBefore(from)) return false;
        if (to != null && at.isAfter(to)) return false;
        return true;
    }

    private static Double median(List<Double> xs) {
        if (xs.isEmpty()) return null;
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        double m = (n % 2 == 1) ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        return round(m, 4);
    }

    private static double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }

    // =============================================================== assignment internals

    /** Small resolved view of an ASSIGNMENT_POOL record. */
    private record PoolSpec(List<String> members, List<String> roles, List<String> supervisors,
                            List<String> supervisorRoles, String strategy, Integer slaHours) {
        static PoolSpec empty() {
            return new PoolSpec(List.of(), List.of(), List.of(), List.of(), "MANUAL", null);
        }
    }

    @SuppressWarnings("unchecked")
    private PoolSpec resolvePool(String queueKey) {
        Optional<MasterRecordDto> rec = masters.assignmentPool(queueKey);
        if (rec.isEmpty()) return PoolSpec.empty();
        Map<String, Object> p = rec.get().payload();
        if (p == null) return PoolSpec.empty();
        List<String> members = asStringList(p.get("members"));
        List<String> roles = asStringList(p.get("roles"));
        List<String> supervisors = asStringList(p.get("supervisors"));
        List<String> supervisorRoles = asStringList(p.get("supervisorRoles"));
        String strategy = p.get("strategy") == null ? "ROUND_ROBIN"
                : String.valueOf(p.get("strategy")).trim().toUpperCase(Locale.ROOT);
        Integer sla = p.get("slaHours") instanceof Number n ? n.intValue() : null;
        return new PoolSpec(members, roles, supervisors, supervisorRoles, strategy, sla);
    }

    /**
     * Resolve an assignee from the pool per its strategy, skipping OOO members
     * (delegating where a delegate is set). Returns null when nobody can be
     * auto-assigned (MANUAL strategy, empty pool, or everyone OOO) — the task then
     * sits OPEN in the queue.
     */
    private String assignFromPool(String queueKey, PoolSpec pool) {
        List<String> members = pool.members();
        if (blank(queueKey) || members.isEmpty() || "MANUAL".equals(pool.strategy())) {
            return null;
        }
        if ("LEAST_LOADED".equals(pool.strategy())) {
            return leastLoaded(members);
        }
        return roundRobin(queueKey, members);
    }

    private String roundRobin(String queueKey, List<String> members) {
        QueueCursor cursor = cursors.findByQueueKey(queueKey).orElseGet(() -> {
            QueueCursor c = new QueueCursor();
            c.setQueueKey(queueKey);
            c.setLastIndex(-1);
            return c;
        });
        int n = members.size();
        for (int step = 1; step <= n; step++) {
            int idx = Math.floorMod(cursor.getLastIndex() + step, n);
            String candidate = members.get(idx);
            Ooo ooo = oooFor(candidate);
            if (!ooo.ooo()) {
                cursor.setLastIndex(idx);
                cursors.save(cursor);
                return candidate;
            }
            if (!blank(ooo.delegateTo()) && !oooFor(ooo.delegateTo()).ooo()) {
                cursor.setLastIndex(idx);
                cursors.save(cursor);
                return ooo.delegateTo();
            }
            // else this member is OOO with no usable delegate — skip to the next.
        }
        // Everyone OOO with no delegate: advance one slot so we don't stall, leave unassigned.
        cursor.setLastIndex(Math.floorMod(cursor.getLastIndex() + 1, n));
        cursors.save(cursor);
        return null;
    }

    private String leastLoaded(List<String> members) {
        String best = null;
        long bestLoad = Long.MAX_VALUE;
        for (String m : members) {
            Ooo ooo = oooFor(m);
            String candidate = m;
            if (ooo.ooo()) {
                if (!blank(ooo.delegateTo()) && !oooFor(ooo.delegateTo()).ooo()) {
                    candidate = ooo.delegateTo();
                } else {
                    continue;   // skip an OOO member with no usable delegate
                }
            }
            long load = items.countByAssigneeIgnoreCaseAndStatusIn(candidate, OPEN_STATES);
            if (load < bestLoad) {
                bestLoad = load;
                best = candidate;
            }
        }
        return best;
    }

    /** Out-of-office status for an actor, resolved from OOO_CALENDAR. */
    private record Ooo(boolean ooo, String delegateTo) {
        static Ooo notOoo() {
            return new Ooo(false, null);
        }
    }

    private Ooo oooFor(String actor) {
        Optional<MasterRecordDto> rec = masters.ooo(actor);
        if (rec.isEmpty() || rec.get().payload() == null) return Ooo.notOoo();
        Map<String, Object> p = rec.get().payload();
        String delegate = p.get("delegateTo") == null ? null : String.valueOf(p.get("delegateTo"));
        Instant from = parseInstant(p.get("from"));
        Instant to = parseInstant(p.get("to"));
        Instant now = Instant.now();
        boolean active = (from == null || !now.isBefore(from)) && (to == null || !now.isAfter(to));
        return active ? new Ooo(true, delegate) : Ooo.notOoo();
    }

    // =============================================================== membership / supervisor gates

    private boolean isMember(PoolSpec pool, String actor) {
        if (blank(actor)) return false;
        if (containsIgnoreCase(pool.members(), actor)) return true;
        // Fall back to directory roles when the pool is role-based.
        if (!pool.roles().isEmpty()) {
            Set<String> held = rolesFor(actor);
            if (held != null && intersectsIgnoreCase(held, pool.roles())) return true;
        }
        return false;
    }

    private boolean isSupervisor(PoolSpec pool, String actor) {
        if (blank(actor)) return false;
        if (containsIgnoreCase(pool.supervisors(), actor)) return true;
        if (!pool.supervisorRoles().isEmpty()) {
            Set<String> held = rolesFor(actor);
            if (held != null && intersectsIgnoreCase(held, pool.supervisorRoles())) return true;
        }
        return false;
    }

    private Set<String> rolesFor(String actor) {
        if (actorDirectory == null) return null;
        try {
            return actorDirectory.rolesFor(actor);
        } catch (Exception e) {
            log.warn("ACTOR_ROLE lookup failed for {} ({}) — treating as no roles", actor, e.getMessage());
            return null;
        }
    }

    // =============================================================== helpers

    private String originatorOf(String taskRef) {
        return events.findByWorkItemRefAndEventOrderByIdAsc(taskRef, "CREATED").stream()
                .map(WorkItemEvent::getActor)
                .filter(a -> !blank(a) && !"system".equalsIgnoreCase(a) && !"engine".equalsIgnoreCase(a))
                .findFirst().orElse(null);
    }

    private void appendEvent(String taskRef, String event, String actor, String actorType, String note) {
        WorkItemEvent e = new WorkItemEvent();
        e.setWorkItemRef(taskRef);
        e.setEvent(event);
        e.setActor(blank(actor) ? "system" : actor);
        e.setActorType(actorType);
        e.setNote(note);
        events.save(e);
    }

    private void stampCreate(String actor, String actorType, WorkItem wi) {
        String summary = "Task %s opened for %s (%s)".formatted(wi.getTaskType(), wi.getSubjectRef(), wi.getStatus());
        Map<String, Object> detail = Map.of("taskRef", wi.getTaskRef(), "queueKey", str(wi.getQueueKey()),
                "assignee", str(wi.getAssignee()), "subjectRef", str(wi.getSubjectRef()));
        switch (actorType) {
            case "HUMAN" -> audit.human(actor, "TASK_CREATED", "WorkItem", wi.getTaskRef(), summary, detail);
            case "AI" -> audit.ai(actor, "TASK_CREATED", "WorkItem", wi.getTaskRef(), summary, detail);
            default -> audit.engine("TASK_CREATED", "WorkItem", wi.getTaskRef(), summary, detail);
        }
    }

    private String freshRef() {
        for (int i = 0; i < 10; i++) {
            String ref = "TSK-" + rand6();
            if (!items.existsByTaskRef(ref)) return ref;
        }
        return "TSK-" + rand6() + (System.nanoTime() % 100);
    }

    private static String rand6() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String normalizeActorType(String raw, String actor) {
        if (raw != null && !raw.isBlank()) {
            String upper = raw.trim().toUpperCase(Locale.ROOT);
            if ("HUMAN".equals(upper) || "AI".equals(upper) || "SYSTEM".equals(upper)) return upper;
        }
        return (blank(actor) || "system".equalsIgnoreCase(actor) || "engine".equalsIgnoreCase(actor))
                ? "SYSTEM" : "HUMAN";
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object item : l) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        if (list == null || v == null) return false;
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(v)) return true;
        }
        return false;
    }

    private static boolean intersectsIgnoreCase(Set<String> held, List<String> wanted) {
        for (String w : wanted) {
            for (String h : held) {
                if (w != null && w.equalsIgnoreCase(h)) return true;
            }
        }
        return false;
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return null;
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
