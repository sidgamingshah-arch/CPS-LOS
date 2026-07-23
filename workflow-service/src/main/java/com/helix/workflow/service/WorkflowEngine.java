package com.helix.workflow.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.workflow.client.DefinitionClient;
import com.helix.workflow.dto.WorkflowDefinitionDto;
import com.helix.workflow.entity.WorkItem;
import com.helix.workflow.entity.WorkflowInstance;
import com.helix.workflow.entity.WorkflowStageState;
import com.helix.workflow.entity.WorkflowTransition;
import com.helix.workflow.dto.TaskDtos.CreateTaskRequest;
import com.helix.workflow.repo.WorkflowInstanceRepository;
import com.helix.workflow.repo.WorkflowStageStateRepository;
import com.helix.workflow.repo.WorkflowTransitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The engine: materialise stage states from the pack, advance/record transitions
 * under the pack-declared guard contract, and sweep SLAs.
 *
 * <p><b>Guard contract</b> (the governed core):
 * <ol>
 *   <li>Ordering — target must be the current IN_PROGRESS stage or the next
 *       PENDING one; no skipping a stage whose {@code humanGate=true}.</li>
 *   <li>Named human on gates — {@code humanGate=true} stages require a
 *       {@code HUMAN} actorType; blank/{@code SYSTEM}/{@code AI} is 403.</li>
 *   <li>Autonomy honoured — {@code autonomy=A} stages may be completed by
 *       {@code AI}/{@code SYSTEM}; {@code D} or any {@code humanGate} demands
 *       {@code HUMAN}.</li>
 *   <li>Authoritative-figure invariant — the engine stores stage status only,
 *       never credit figures. (Verified by e2e.)</li>
 * </ol>
 *
 * <p>{@link #record(String, String, String, String, String)} is the lower-bar
 * strangler path: domain services CALL this to assert "X happened" without
 * having to satisfy the strict ordering check. The full guard contract runs on
 * {@link #advance(String, String, String, String, String)}.</p>
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowInstanceRepository instances;
    private final WorkflowStageStateRepository stages;
    private final WorkflowTransitionRepository transitions;
    private final DefinitionClient definitions;
    private final AuditService audit;

    /**
     * Optional case-management mirror. When a stage declares a {@code queueKey},
     * entering it auto-creates a WorkItem — a best-effort mirror that must NEVER
     * fail the domain transition (every call is wrapped in try/catch + log). Same
     * module / same DB / same transaction, so no cross-service or cross-connection
     * concern (mirrors how {@code AuditService} joins the caller's transaction).
     */
    private final WorkItemService workItems;

    /**
     * Per-item short transaction for the auto-movement sweep. Default REQUIRED propagation (NOT
     * REQUIRES_NEW): the sweep orchestrator is non-transactional, so each call opens its own
     * fresh transaction and commits before the next — the single-connection SQLite pool never
     * deadlocks, and one failing item cannot roll back the others.
     */
    private final TransactionTemplate txTemplate;

    /**
     * Master guard for the auto-movement sweep. Defaults TRUE: it is safe because the sweep only
     * ever touches stages / items that EXPLICITLY declared an auto key, and no existing seeded
     * pack or task does — so a default-on sweep is a strict no-op on today's data. Flip to false
     * via {@code helix.workflow.auto-movement.enabled} (env {@code HELIX_WORKFLOW_AUTOMOVEMENT_ENABLED}).
     */
    private final boolean autoMovementEnabled;

    public WorkflowEngine(WorkflowInstanceRepository instances, WorkflowStageStateRepository stages,
                          WorkflowTransitionRepository transitions, DefinitionClient definitions,
                          AuditService audit,
                          @Autowired(required = false) WorkItemService workItems,
                          PlatformTransactionManager txManager,
                          @Value("${helix.workflow.auto-movement.enabled:true}") boolean autoMovementEnabled) {
        this.instances = instances;
        this.stages = stages;
        this.transitions = transitions;
        this.definitions = definitions;
        this.audit = audit;
        this.workItems = workItems;
        this.txTemplate = new TransactionTemplate(txManager);
        this.autoMovementEnabled = autoMovementEnabled;
    }

    // =============================================================== materialise

    /**
     * Idempotent: if an instance already exists for this application reference,
     * returns the existing view (no re-materialise). Pins the resolved pack's
     * {@code code}+{@code version}.
     */
    @Transactional
    public WorkflowInstance materialise(String applicationReference, String jurisdiction,
                                         String segment, String actor) {
        return instances.findByApplicationReference(applicationReference).orElseGet(() -> {
            WorkflowDefinitionDto def = definitions.resolve(jurisdiction, segment);
            WorkflowInstance wf = new WorkflowInstance();
            wf.setApplicationReference(applicationReference);
            wf.setDefinitionCode(def.code());
            wf.setDefinitionVersion(def.version());
            wf.setJurisdiction(jurisdiction);
            wf.setSegment(segment);
            wf.setStatus("ACTIVE");
            wf.setStartedAt(Instant.now());
            WorkflowInstance saved = instances.save(wf);

            int ordinal = 0;
            String firstKey = null;
            for (Map<String, Object> s : def.stages()) {
                WorkflowStageState state = new WorkflowStageState();
                state.setInstanceId(saved.getId());
                state.setOrdinal(ordinal);
                String key = String.valueOf(s.get("key"));
                state.setStageKey(key);
                state.setLabel(String.valueOf(s.getOrDefault("label", key)));
                state.setAutonomy(String.valueOf(s.getOrDefault("autonomy", "—")));
                state.setAiAllowed(Boolean.TRUE.equals(s.get("ai")));
                state.setHumanGate(Boolean.TRUE.equals(s.get("humanGate")));
                Object slaRaw = s.get("slaHours");
                state.setSlaHours(slaRaw instanceof Number n ? n.intValue() : 24);
                // OPTIONAL additive keys — null/absent for every existing seeded pack, so the
                // engine keeps its strict linear behaviour byte-identical when they are absent.
                Object pg = s.get("parallelGroup");
                state.setParallelGroup(pg == null ? null : String.valueOf(pg));
                Object jp = s.get("joinPolicy");
                state.setJoinPolicy(jp == null ? null : String.valueOf(jp));
                Object qk = s.get("queueKey");
                state.setQueueKey(qk == null ? null : String.valueOf(qk));
                // OPTIONAL auto-movement keys — null/absent for every existing seeded pack, so
                // the auto-movement sweep is a strict no-op on today's lifecycles.
                state.setAutoAdvanceAfterHours(s.get("autoAdvanceAfterHours") instanceof Number aa ? aa.intValue() : null);
                state.setAutoLapseAfterHours(s.get("autoLapseAfterHours") instanceof Number al ? al.intValue() : null);
                Object lts = s.get("autoLapseToStatus");
                state.setAutoLapseToStatus(lts == null ? null : String.valueOf(lts));
                state.setStatus("PENDING");
                stages.save(state);
                if (ordinal == 0) {
                    firstKey = key;
                    // Auto-enter the first stage so the lifecycle has a current position immediately.
                    state.setStatus("IN_PROGRESS");
                    state.setEnteredAt(Instant.now());
                    state.setSlaDueAt(Instant.now().plusSeconds(state.getSlaHours() * 3600L));
                }
                ordinal++;
            }
            saved.setCurrentStageKey(firstKey);
            instances.save(saved);

            // Co-enter any parallel-group siblings of the first stage, and fire the
            // stage-entry task mirror. Both are no-ops for linear packs (no parallelGroup /
            // no queueKey), preserving the existing materialise behaviour exactly.
            List<WorkflowStageState> materialised = stages.findByInstanceIdOrderByOrdinalAsc(saved.getId());
            for (WorkflowStageState first : materialised) {
                if ("IN_PROGRESS".equals(first.getStatus())) {
                    coEnterParallelSiblings(materialised, first, Instant.now());
                    fireStageEntryHook(applicationReference, first, actor);
                    break;
                }
            }

            WorkflowTransition tx = new WorkflowTransition();
            tx.setInstanceId(saved.getId());
            tx.setToStageKey(firstKey);
            tx.setKind("MATERIALISED");
            tx.setActor(actor == null ? "system" : actor);
            tx.setActorType(actor == null ? "SYSTEM" : "HUMAN");
            tx.setNote("Materialised " + def.stages().size() + " stages from " + def.code() + " v" + def.version());
            transitions.save(tx);

            audit.engine("WORKFLOW_MATERIALISED", "WorkflowInstance", applicationReference,
                    "Materialised " + def.stages().size() + " stages from " + def.code(),
                    Map.of("definitionCode", def.code(), "definitionVersion", def.version(),
                            "jurisdiction", jurisdiction == null ? "" : jurisdiction,
                            "segment", segment == null ? "" : segment,
                            "stageCount", def.stages().size()));
            return saved;
        });
    }

    // =============================================================== advance (the guarded path)

    @Transactional
    public WorkflowInstance advance(String applicationReference, String targetStageKey,
                                     String actorTypeRaw, String note, String actor) {
        WorkflowInstance wf = require(applicationReference);
        if (!"ACTIVE".equals(wf.getStatus())) {
            throw ApiException.conflict("Workflow is " + wf.getStatus());
        }
        List<WorkflowStageState> all = stages.findByInstanceIdOrderByOrdinalAsc(wf.getId());
        if (all.isEmpty()) throw ApiException.conflict("No stages materialised on " + applicationReference);

        WorkflowStageState target = null;
        for (WorkflowStageState s : all) {
            if (s.getStageKey().equals(targetStageKey)) { target = s; break; }
        }
        if (target == null) {
            throw ApiException.badRequest("Stage '" + targetStageKey + "' is not part of this workflow");
        }
        if ("COMPLETE".equals(target.getStatus()) || "SKIPPED".equals(target.getStatus())) {
            throw ApiException.conflict("Stage " + targetStageKey + " is already " + target.getStatus());
        }
        // 1. Ordering — every PENDING stage before this with humanGate=true blocks a skip.
        for (WorkflowStageState s : all) {
            if (s.getOrdinal() >= target.getOrdinal()) break;
            if (s.isHumanGate() && !"COMPLETE".equals(s.getStatus()) && !"SKIPPED".equals(s.getStatus())) {
                throw ApiException.conflict("Cannot advance past a human-gated PENDING stage: "
                        + s.getStageKey() + " is " + s.getStatus());
            }
        }

        // 2 & 3. Guard contract on the target stage itself.
        String actorType = normalizeActorType(actorTypeRaw, actor);
        if (target.isHumanGate() && !"HUMAN".equals(actorType)) {
            throw ApiException.forbiddenAutonomy(
                    "Stage " + targetStageKey + " is human-gated — actor must be HUMAN, got " + actorType);
        }
        if ("D".equalsIgnoreCase(target.getAutonomy()) && !"HUMAN".equals(actorType)) {
            throw ApiException.forbiddenAutonomy(
                    "Stage " + targetStageKey + " has autonomy=D (decision gate) — actor must be HUMAN");
        }
        if (("A".equalsIgnoreCase(target.getAutonomy()) || "C".equalsIgnoreCase(target.getAutonomy()))
                && "HUMAN".equals(actorType) && actor != null && !actor.isBlank()) {
            // Humans can still ratify autonomous/consulting stages — that is fine.
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy(
                    "A named actor (X-Actor header) is required to advance a workflow stage");
        }

        // Carry: any PENDING stage *strictly before* target is marked SKIPPED (only legal when no humanGate sat in between, which we checked).
        Instant now = Instant.now();
        for (WorkflowStageState s : all) {
            if (s.getOrdinal() >= target.getOrdinal()) break;
            if (!"COMPLETE".equals(s.getStatus()) && !"SKIPPED".equals(s.getStatus())) {
                s.setStatus("SKIPPED");
                s.setCompletedAt(now);
                stages.save(s);
            }
        }

        // Complete target.
        target.setStatus("COMPLETE");
        target.setCompletedAt(now);
        target.setCompletedBy(actor);
        target.setCompletedByType(actorType);
        target.setNote(note);
        if (target.getSlaDueAt() != null && now.isAfter(target.getSlaDueAt())) {
            target.setSlaBreached(true);
        }
        stages.save(target);

        // Enter next stage — parallel-group aware. For a linear pack (no parallelGroup)
        // this reduces byte-for-byte to the original "first PENDING after target" logic.
        EnterResult entered = advanceCursorAfterComplete(all, target, wf, now, applicationReference, actor);
        WorkflowStageState next = entered.next();
        if (entered.instanceCompleted()) {
            wf.setStatus("COMPLETED");
            wf.setCompletedAt(now);
        }
        rollupSlaBreach(wf);
        instances.save(wf);

        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(targetStageKey);
        tx.setToStageKey(next == null ? (entered.instanceCompleted() ? null : wf.getCurrentStageKey())
                : next.getStageKey());
        tx.setKind(entered.instanceCompleted() ? "COMPLETED" : "ADVANCED");
        tx.setActor(actor);
        tx.setActorType(actorType);
        tx.setNote(note);
        transitions.save(tx);

        if ("HUMAN".equals(actorType)) {
            audit.human(actor, "WORKFLOW_STAGE_ADVANCED", "WorkflowInstance", applicationReference,
                    "Stage " + targetStageKey + " completed; entered " + (next == null ? "(end)" : next.getStageKey()),
                    Map.of("stageKey", targetStageKey,
                            "nextStageKey", next == null ? "" : next.getStageKey()));
        } else if ("AI".equals(actorType)) {
            audit.ai(actor, "WORKFLOW_STAGE_ADVANCED", "WorkflowInstance", applicationReference,
                    "Stage " + targetStageKey + " completed by AI",
                    Map.of("stageKey", targetStageKey));
        } else {
            audit.engine("WORKFLOW_STAGE_ADVANCED", "WorkflowInstance", applicationReference,
                    "Stage " + targetStageKey + " completed",
                    Map.of("stageKey", targetStageKey));
        }
        return wf;
    }

    // =============================================================== record (best-effort)

    /**
     * Best-effort: assert that the named stage happened, without enforcing the
     * ordering guard. Used by domain services at their existing transition
     * points (strangler wiring) so a workflow-service outage / mismatched pack
     * never breaks the lifecycle. Still respects {@code humanGate}/autonomy.
     */
    @Transactional
    public WorkflowInstance record(String applicationReference, String stageKey,
                                    String actorTypeRaw, String note, String actor) {
        WorkflowInstance wf = require(applicationReference);
        WorkflowStageState stage = stages.findByInstanceIdAndStageKey(wf.getId(), stageKey)
                .orElseThrow(() -> ApiException.badRequest("Stage '" + stageKey + "' not on this workflow"));
        if ("COMPLETE".equals(stage.getStatus())) {
            // Already recorded — idempotent.
            return wf;
        }
        String actorType = normalizeActorType(actorTypeRaw, actor);
        if (stage.isHumanGate() && !"HUMAN".equals(actorType)) {
            throw ApiException.forbiddenAutonomy(
                    "Stage " + stageKey + " is human-gated — cannot record as " + actorType);
        }
        Instant now = Instant.now();
        if (stage.getEnteredAt() == null) {
            stage.setEnteredAt(now);
            stage.setSlaDueAt(now.plusSeconds(stage.getSlaHours() * 3600L));
        }
        stage.setStatus("COMPLETE");
        stage.setCompletedAt(now);
        stage.setCompletedBy(actor);
        stage.setCompletedByType(actorType);
        stage.setNote(note);
        if (stage.getSlaDueAt() != null && now.isAfter(stage.getSlaDueAt())) {
            stage.setSlaBreached(true);
        }
        stages.save(stage);

        // If recording the currentStage, advance the cursor through the SAME parallel-aware
        // helper advance() uses, so a join policy is honoured rather than the old single-stage
        // next-pending. For a linear pack (no parallelGroup) this is byte-identical to the
        // previous "first PENDING after this stage, else complete the instance" behaviour.
        if (stageKey.equals(wf.getCurrentStageKey())) {
            List<WorkflowStageState> all = stages.findByInstanceIdOrderByOrdinalAsc(wf.getId());
            EnterResult entered = advanceCursorAfterComplete(all, stage, wf, now, applicationReference, actor);
            if (entered.instanceCompleted()) {
                wf.setStatus("COMPLETED");
                wf.setCompletedAt(now);
            }
            rollupSlaBreach(wf);
            instances.save(wf);
        }

        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(stageKey);
        tx.setToStageKey(wf.getCurrentStageKey());
        tx.setKind("RECORDED");
        tx.setActor(actor);
        tx.setActorType(actorType);
        tx.setNote(note);
        transitions.save(tx);

        audit.engine("WORKFLOW_STAGE_RECORDED", "WorkflowInstance", applicationReference,
                "Recorded " + stageKey + " by " + actorType,
                Map.of("stageKey", stageKey, "actorType", actorType));
        return wf;
    }

    // =============================================================== block / unblock

    @Transactional
    public WorkflowStageState block(String applicationReference, String stageKey,
                                     String reason, String actor) {
        WorkflowInstance wf = require(applicationReference);
        WorkflowStageState s = stages.findByInstanceIdAndStageKey(wf.getId(), stageKey)
                .orElseThrow(() -> ApiException.badRequest("Stage '" + stageKey + "' not on this workflow"));
        if ("COMPLETE".equals(s.getStatus()) || "SKIPPED".equals(s.getStatus())) {
            throw ApiException.conflict("Cannot block a " + s.getStatus() + " stage");
        }
        s.setStatus("BLOCKED");
        s.setBlockedReason(reason);
        stages.save(s);
        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(stageKey);
        tx.setToStageKey(stageKey);
        tx.setKind("BLOCKED");
        tx.setActor(actor);
        tx.setActorType(normalizeActorType(null, actor));
        tx.setNote(reason);
        transitions.save(tx);
        audit.human(actor == null ? "system" : actor, "WORKFLOW_STAGE_BLOCKED",
                "WorkflowInstance", applicationReference,
                "Blocked " + stageKey + " — " + reason, Map.of("stageKey", stageKey));
        return s;
    }

    @Transactional
    public WorkflowStageState unblock(String applicationReference, String stageKey, String actor) {
        WorkflowInstance wf = require(applicationReference);
        WorkflowStageState s = stages.findByInstanceIdAndStageKey(wf.getId(), stageKey)
                .orElseThrow(() -> ApiException.badRequest("Stage '" + stageKey + "' not on this workflow"));
        if (!"BLOCKED".equals(s.getStatus())) {
            throw ApiException.conflict("Stage is not BLOCKED");
        }
        s.setStatus(stageKey.equals(wf.getCurrentStageKey()) ? "IN_PROGRESS" : "PENDING");
        s.setBlockedReason(null);
        stages.save(s);
        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(stageKey);
        tx.setToStageKey(stageKey);
        tx.setKind("UNBLOCKED");
        tx.setActor(actor);
        tx.setActorType(normalizeActorType(null, actor));
        transitions.save(tx);
        audit.human(actor == null ? "system" : actor, "WORKFLOW_STAGE_UNBLOCKED",
                "WorkflowInstance", applicationReference,
                "Unblocked " + stageKey, Map.of("stageKey", stageKey));
        return s;
    }

    // =============================================================== send-back / withdraw (append-only)

    /**
     * ADDITIVE, append-only: re-arm the workflow to a prior stage. The target stage
     * returns to IN_PROGRESS and every stage strictly after it is reset to PENDING
     * (so any downstream {@code humanGate} must be re-satisfied). History is never
     * deleted — a SENT_BACK transition row is appended and the earlier transitions
     * remain. Backward-compatible: existing packs never call this path.
     */
    @Transactional
    public WorkflowInstance sendBack(String applicationReference, String toStageKey, String note, String actor) {
        if (toStageKey == null || toStageKey.isBlank()) {
            throw ApiException.badRequest("toStageKey is required to send a workflow back");
        }
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to send a workflow back");
        }
        WorkflowInstance wf = require(applicationReference);
        List<WorkflowStageState> all = stages.findByInstanceIdOrderByOrdinalAsc(wf.getId());
        WorkflowStageState target = null;
        for (WorkflowStageState s : all) {
            if (s.getStageKey().equals(toStageKey)) { target = s; break; }
        }
        if (target == null) {
            throw ApiException.badRequest("Stage '" + toStageKey + "' is not part of this workflow");
        }
        // Send-back must go BACKWARDS: the target must sit strictly before the current frontier,
        // never at/ahead of it (that would jump the lifecycle forward past still-PENDING gates).
        int frontier = -1;
        for (WorkflowStageState s : all) {
            if (s.getStageKey().equals(wf.getCurrentStageKey())) { frontier = s.getOrdinal(); break; }
        }
        if (frontier < 0) {
            for (WorkflowStageState s : all) {
                if ("COMPLETE".equals(s.getStatus()) || "IN_PROGRESS".equals(s.getStatus())) {
                    frontier = Math.max(frontier, s.getOrdinal());
                }
            }
        }
        if (target.getOrdinal() >= frontier) {
            throw ApiException.conflict("Send-back must target a stage strictly before the current stage ("
                    + wf.getCurrentStageKey() + ") — cannot jump forward to " + toStageKey);
        }
        String from = wf.getCurrentStageKey();
        Instant now = Instant.now();
        for (WorkflowStageState s : all) {
            if (s.getOrdinal() == target.getOrdinal()) {
                s.setStatus("IN_PROGRESS");
                s.setEnteredAt(now);
                s.setSlaDueAt(now.plusSeconds(s.getSlaHours() * 3600L));
                s.setCompletedAt(null);
                s.setCompletedBy(null);
                s.setCompletedByType(null);
                s.setBlockedReason(null);
                s.setSlaBreached(false);
                stages.save(s);
            } else if (s.getOrdinal() > target.getOrdinal()
                    && ("COMPLETE".equals(s.getStatus()) || "SKIPPED".equals(s.getStatus())
                        || "BLOCKED".equals(s.getStatus()) || "IN_PROGRESS".equals(s.getStatus()))) {
                // Re-arm the humanGate: the downstream work must be redone.
                s.setStatus("PENDING");
                s.setEnteredAt(null);
                s.setCompletedAt(null);
                s.setCompletedBy(null);
                s.setCompletedByType(null);
                s.setSlaDueAt(null);
                s.setSlaBreached(false);
                s.setBlockedReason(null);
                stages.save(s);
            }
        }
        wf.setStatus("ACTIVE");
        wf.setCompletedAt(null);
        wf.setCurrentStageKey(toStageKey);
        instances.save(wf);

        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(from);
        tx.setToStageKey(toStageKey);
        tx.setKind("SENT_BACK");
        tx.setActor(actor);
        tx.setActorType("HUMAN");
        tx.setNote(note);
        transitions.save(tx);

        audit.human(actor, "WORKFLOW_SENT_BACK", "WorkflowInstance", applicationReference,
                "Sent back from " + from + " to " + toStageKey, Map.of("from", from == null ? "" : from,
                        "toStageKey", toStageKey));
        fireStageEntryHook(applicationReference, target, actor);
        return wf;
    }

    /**
     * ADDITIVE, append-only: withdraw (cancel) an in-flight instance. Terminal; the
     * transition history is preserved. Backward-compatible: existing packs never
     * call this path.
     */
    @Transactional
    public WorkflowInstance withdraw(String applicationReference, String note, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named actor (X-Actor) is required to withdraw a workflow");
        }
        WorkflowInstance wf = require(applicationReference);
        if (!"ACTIVE".equals(wf.getStatus())) {
            throw ApiException.conflict("Workflow is " + wf.getStatus() + " — only an ACTIVE instance can be withdrawn");
        }
        Instant now = Instant.now();
        wf.setStatus("WITHDRAWN");
        wf.setCompletedAt(now);
        instances.save(wf);
        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(wf.getCurrentStageKey());
        tx.setToStageKey(null);
        tx.setKind("WITHDRAWN");
        tx.setActor(actor);
        tx.setActorType(normalizeActorType(null, actor));
        tx.setNote(note);
        transitions.save(tx);
        audit.human(actor, "WORKFLOW_WITHDRAWN", "WorkflowInstance",
                applicationReference, "Withdrew workflow at " + wf.getCurrentStageKey(),
                Map.of("stageKey", wf.getCurrentStageKey() == null ? "" : wf.getCurrentStageKey()));
        return wf;
    }

    // =============================================================== reads

    @Transactional(readOnly = true)
    public WorkflowInstance require(String applicationReference) {
        return instances.findByApplicationReference(applicationReference)
                .orElseThrow(() -> ApiException.notFound("No workflow instance for " + applicationReference));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(String applicationReference) {
        WorkflowInstance wf = require(applicationReference);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", wf);
        out.put("stages", stages.findByInstanceIdOrderByOrdinalAsc(wf.getId()));
        out.put("transitions", transitions.findByInstanceIdOrderByIdAsc(wf.getId()));
        return out;
    }

    @Transactional(readOnly = true)
    public List<WorkflowInstance> activeInstances() {
        return instances.findByStatusOrderByIdDesc("ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> slaBreaches() {
        List<WorkflowStageState> breached = new ArrayList<>(stages
                .findBySlaDueAtBeforeAndStatusIn(Instant.now(), List.of("IN_PROGRESS", "PENDING", "BLOCKED")));
        // Also include closed stages where we already flagged a breach.
        for (WorkflowStageState s : stages.findByStatus("COMPLETE")) {
            if (s.isSlaBreached()) breached.add(s);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkflowStageState s : breached) {
            WorkflowInstance wf = instances.findById(s.getInstanceId()).orElse(null);
            if (wf == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationReference", wf.getApplicationReference());
            row.put("jurisdiction", wf.getJurisdiction());
            row.put("segment", wf.getSegment());
            row.put("stageKey", s.getStageKey());
            row.put("label", s.getLabel());
            row.put("status", s.getStatus());
            row.put("slaDueAt", s.getSlaDueAt());
            row.put("slaHours", s.getSlaHours());
            row.put("enteredAt", s.getEnteredAt());
            row.put("humanGate", s.isHumanGate());
            out.add(row);
        }
        return out;
    }

    // =============================================================== SLA sweep

    /** Scheduled sweep — flags stages past their slaDueAt and rolls up to the instance. */
    @Scheduled(cron = "${helix.workflow.sla-sweep-cron:0 */5 * * * *}")
    @Transactional
    public void slaSweep() {
        try {
            slaSweepNow();
        } catch (Exception e) {
            // Never let the sweep crash the scheduler.
        }
    }

    @Transactional
    public int slaSweepNow() {
        Instant cutoff = Instant.now();
        List<WorkflowStageState> overdue = stages.findBySlaDueAtBeforeAndStatusIn(
                cutoff, List.of("IN_PROGRESS", "PENDING", "BLOCKED"));
        int flagged = 0;
        for (WorkflowStageState s : overdue) {
            if (!s.isSlaBreached()) {
                s.setSlaBreached(true);
                stages.save(s);
                WorkflowInstance wf = instances.findById(s.getInstanceId()).orElse(null);
                if (wf != null && !wf.isSlaBreached()) {
                    wf.setSlaBreached(true);
                    instances.save(wf);
                    audit.engine("WORKFLOW_SLA_BREACHED", "WorkflowInstance",
                            wf.getApplicationReference(),
                            "Stage " + s.getStageKey() + " breached SLA",
                            Map.of("stageKey", s.getStageKey(), "slaHours", s.getSlaHours()));
                }
                flagged++;
            }
        }
        return flagged;
    }

    // =============================================================== auto-movement sweep

    /**
     * Scheduled generalized case movement — config-driven, condition-based auto-advance /
     * auto-lapse. A strict no-op unless a stage / work-item explicitly declared an auto key,
     * so it never touches a lifecycle that did not opt in. Wrapped so it can never crash the
     * scheduler (mirrors {@link #slaSweep()}).
     */
    @Scheduled(cron = "${helix.workflow.auto-movement.sweep-cron:0 */5 * * * *}")
    public void autoMovementSweep() {
        if (!autoMovementEnabled) return;
        try {
            autoMovementSweepNow();
        } catch (Exception e) {
            // Never let the sweep crash the scheduler.
        }
    }

    /**
     * Run one auto-movement pass and return the number of cases moved (advanced + lapsed
     * instances + lapsed work-items). Each candidate is handled in its OWN short transaction
     * (see {@link #txTemplate}) inside a try/catch, so a single bad item is skipped rather than
     * aborting the whole sweep — the same resilience posture as {@link #slaSweep()}.
     *
     * <p>Governed guarantees:
     * <ul>
     *   <li>Only stages / items that EXPLICITLY declared an auto key are eligible — everything
     *       else is byte-identical to before this feature existed.</li>
     *   <li>A human-gated ({@code humanGate=true}) or decision-gate ({@code autonomy=D}) stage is
     *       NEVER auto-advanced — a named human still owns that transition.</li>
     *   <li>Every movement appends a WorkflowTransition / WorkItemEvent and stamps an audit event
     *       with actorType SYSTEM (actor {@code system.auto-movement}).</li>
     * </ul>
     */
    public int autoMovementSweepNow() {
        if (!autoMovementEnabled) return 0;
        Instant now = Instant.now();
        int moved = 0;

        // 1. Stage-driven auto-advance / auto-lapse on ACTIVE instances (one move per instance
        //    per pass — repeated passes progress a lifecycle further, exactly like the SLA sweep).
        for (WorkflowInstance snap : instances.findByStatusOrderByIdDesc("ACTIVE")) {
            Long id = snap.getId();
            try {
                Integer r = txTemplate.execute(status -> applyStageAutoMovement(id, now));
                if (r != null) moved += r;
            } catch (Exception e) {
                log.warn("auto-movement sweep skipped instance id {} ({})", id, e.getMessage());
            }
        }

        // 2. Work-item auto-lapse (each in its own short transaction on the WorkItemService bean).
        if (workItems != null) {
            for (WorkItem wiSnap : workItems.autoLapseCandidates()) {
                String ref = wiSnap.getTaskRef();
                try {
                    if (workItems.autoLapse(ref, now)) moved++;
                } catch (Exception e) {
                    log.warn("auto-movement sweep skipped work-item {} ({})", ref, e.getMessage());
                }
            }
        }
        return moved;
    }

    /**
     * Apply at most one stage-driven auto-movement to a single instance, inside a short
     * transaction. Returns 1 if the instance was moved (advanced or lapsed), else 0.
     */
    private int applyStageAutoMovement(Long instanceId, Instant now) {
        WorkflowInstance wf = instances.findById(instanceId).orElse(null);
        if (wf == null || !"ACTIVE".equals(wf.getStatus())) return 0;
        List<WorkflowStageState> all = stages.findByInstanceIdOrderByOrdinalAsc(wf.getId());
        for (WorkflowStageState s : all) {
            if (!"IN_PROGRESS".equals(s.getStatus()) || s.getEnteredAt() == null) continue;
            // Auto-lapse takes priority — it is a terminal expiry of the whole request.
            if (s.getAutoLapseAfterHours() != null
                    && now.isAfter(s.getEnteredAt().plusSeconds(s.getAutoLapseAfterHours() * 3600L))) {
                autoLapseInstance(wf, s, now);
                return 1;
            }
            // Auto-advance — but NEVER over a human gate / decision gate.
            if (s.getAutoAdvanceAfterHours() != null
                    && now.isAfter(s.getEnteredAt().plusSeconds(s.getAutoAdvanceAfterHours() * 3600L))) {
                if (s.isHumanGate() || "D".equalsIgnoreCase(s.getAutonomy())) {
                    log.debug("auto-advance declined for {}/{} — human/decision gate owns it",
                            wf.getApplicationReference(), s.getStageKey());
                    continue;   // a human still owns this transition
                }
                autoAdvanceStage(wf, all, s, now);
                return 1;
            }
        }
        return 0;
    }

    /** Lapse the whole instance to the stage's declared terminal status (default {@code LAPSED}). */
    private void autoLapseInstance(WorkflowInstance wf, WorkflowStageState stage, Instant now) {
        String toStatus = (stage.getAutoLapseToStatus() == null || stage.getAutoLapseToStatus().isBlank())
                ? "LAPSED" : stage.getAutoLapseToStatus().trim().toUpperCase(Locale.ROOT);
        wf.setStatus(toStatus);
        wf.setCompletedAt(now);
        instances.save(wf);
        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(stage.getStageKey());
        tx.setToStageKey(null);
        tx.setKind("AUTO_LAPSED");
        tx.setActor("system.auto-movement");
        tx.setActorType("SYSTEM");
        tx.setNote("Auto-lapsed to " + toStatus + " after " + stage.getAutoLapseAfterHours()
                + "h in stage " + stage.getStageKey());
        transitions.save(tx);
        audit.engine("WORKFLOW_AUTO_LAPSED", "WorkflowInstance", wf.getApplicationReference(),
                "Auto-lapsed to " + toStatus + " from stage " + stage.getStageKey(),
                Map.of("stageKey", stage.getStageKey(), "toStatus", toStatus,
                        "autoLapseAfterHours", stage.getAutoLapseAfterHours()));
    }

    /**
     * Complete the current IN_PROGRESS stage as SYSTEM and enter the next, reusing the same
     * parallel-group-aware cursor helper the human {@link #advance} path uses. Appends an
     * AUTO_ADVANCED transition and an engine audit event.
     */
    private void autoAdvanceStage(WorkflowInstance wf, List<WorkflowStageState> all,
                                  WorkflowStageState target, Instant now) {
        target.setStatus("COMPLETE");
        target.setCompletedAt(now);
        target.setCompletedBy("system.auto-movement");
        target.setCompletedByType("SYSTEM");
        target.setNote("Auto-advanced after " + target.getAutoAdvanceAfterHours() + "h dwell");
        if (target.getSlaDueAt() != null && now.isAfter(target.getSlaDueAt())) {
            target.setSlaBreached(true);
        }
        stages.save(target);

        EnterResult entered = advanceCursorAfterComplete(all, target, wf, now,
                wf.getApplicationReference(), "system.auto-movement");
        WorkflowStageState next = entered.next();
        if (entered.instanceCompleted()) {
            wf.setStatus("COMPLETED");
            wf.setCompletedAt(now);
        }
        rollupSlaBreach(wf);
        instances.save(wf);

        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(target.getStageKey());
        tx.setToStageKey(next == null ? (entered.instanceCompleted() ? null : wf.getCurrentStageKey())
                : next.getStageKey());
        tx.setKind(entered.instanceCompleted() ? "AUTO_COMPLETED" : "AUTO_ADVANCED");
        tx.setActor("system.auto-movement");
        tx.setActorType("SYSTEM");
        tx.setNote("Auto-advanced from " + target.getStageKey());
        transitions.save(tx);

        audit.engine("WORKFLOW_AUTO_ADVANCED", "WorkflowInstance", wf.getApplicationReference(),
                "Stage " + target.getStageKey() + " auto-advanced; entered "
                        + (next == null ? "(end)" : next.getStageKey()),
                Map.of("stageKey", target.getStageKey(),
                        "nextStageKey", next == null ? "" : next.getStageKey(),
                        "autoAdvanceAfterHours", target.getAutoAdvanceAfterHours()));
    }

    // =============================================================== helpers

    /** Outcome of advancing the cursor after a stage completes. */
    private record EnterResult(WorkflowStageState next, boolean instanceCompleted) {
    }

    /**
     * Parallel-group-aware cursor advance. When the completed {@code target} has no
     * {@code parallelGroup} (every existing pack), this is exactly the original
     * behaviour: enter the first PENDING stage after {@code target}, else complete
     * the instance. When {@code target} belongs to a parallel group, the cursor only
     * moves past the group once the group's {@link #joinPolicy} is satisfied;
     * otherwise it points at a still-open sibling and the instance stays ACTIVE.
     */
    private EnterResult advanceCursorAfterComplete(List<WorkflowStageState> all, WorkflowStageState target,
                                                   WorkflowInstance wf, Instant now,
                                                   String applicationReference, String actor) {
        int fromOrdinal = target.getOrdinal();
        if (target.getParallelGroup() != null) {
            List<WorkflowStageState> group = new ArrayList<>();
            int maxOrdinal = target.getOrdinal();
            for (WorkflowStageState s : all) {
                if (target.getParallelGroup().equals(s.getParallelGroup())) {
                    group.add(s);
                    maxOrdinal = Math.max(maxOrdinal, s.getOrdinal());
                }
            }
            int completed = 0;
            for (WorkflowStageState s : group) {
                if ("COMPLETE".equals(s.getStatus()) || "SKIPPED".equals(s.getStatus())) completed++;
            }
            String policy = target.getJoinPolicy();
            if (policy == null) {
                policy = group.stream().map(WorkflowStageState::getJoinPolicy)
                        .filter(p -> p != null).findFirst().orElse("ALL");
            }
            if (!JoinPolicy.satisfied(policy, completed, group.size())) {
                // Group not yet satisfied — keep the cursor on a still-open sibling.
                WorkflowStageState openSibling = group.stream()
                        .filter(s -> "IN_PROGRESS".equals(s.getStatus())).findFirst().orElse(null);
                wf.setCurrentStageKey(openSibling != null ? openSibling.getStageKey() : target.getStageKey());
                return new EnterResult(null, false);
            }
            // Join satisfied (ANY / QUORUM) — retire any still-open siblings so an SLA sweep
            // never flags a passed stage and a later advance() cannot re-complete the group.
            for (WorkflowStageState s : group) {
                if (!"COMPLETE".equals(s.getStatus()) && !"SKIPPED".equals(s.getStatus())) {
                    s.setStatus("SKIPPED");
                    s.setCompletedAt(now);
                    s.setNote("Join " + policy + " satisfied by sibling(s) — stage skipped");
                    stages.save(s);
                }
            }
            fromOrdinal = maxOrdinal;
        }
        WorkflowStageState next = null;
        for (WorkflowStageState s : all) {
            if (s.getOrdinal() > fromOrdinal && "PENDING".equals(s.getStatus())) { next = s; break; }
        }
        if (next != null) {
            next.setStatus("IN_PROGRESS");
            next.setEnteredAt(now);
            next.setSlaDueAt(now.plusSeconds(next.getSlaHours() * 3600L));
            stages.save(next);
            wf.setCurrentStageKey(next.getStageKey());
            coEnterParallelSiblings(all, next, now);
            fireStageEntryHook(applicationReference, next, actor);
            return new EnterResult(next, false);
        }
        return new EnterResult(null, true);
    }

    /** Co-enter the PENDING siblings of a stage that belongs to a parallel group (no-op otherwise). */
    private void coEnterParallelSiblings(List<WorkflowStageState> all, WorkflowStageState entered, Instant now) {
        if (entered.getParallelGroup() == null) return;
        for (WorkflowStageState s : all) {
            if (s.getId() != null && s.getId().equals(entered.getId())) continue;
            if (entered.getParallelGroup().equals(s.getParallelGroup()) && "PENDING".equals(s.getStatus())) {
                s.setStatus("IN_PROGRESS");
                s.setEnteredAt(now);
                s.setSlaDueAt(now.plusSeconds(s.getSlaHours() * 3600L));
                stages.save(s);
            }
        }
    }

    /**
     * Best-effort case-management mirror: when a stage declares a {@code queueKey},
     * entering it auto-creates a WorkItem. A mirror failure must NEVER fail the
     * domain transition — every path here is wrapped in try/catch + log. The dedupe key
     * carries an entry discriminator ({@code enteredAt}), so a single entry stays
     * idempotent but a send-back RE-entry reopens a fresh task (rework) rather than
     * silently returning the stale COMPLETED mirror.
     */
    private void fireStageEntryHook(String applicationReference, WorkflowStageState stage, String actor) {
        if (workItems == null || stage == null || stage.getQueueKey() == null || stage.getQueueKey().isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stageKey", stage.getStageKey());
            payload.put("stageLabel", stage.getLabel());
            payload.put("source", "WORKFLOW_STAGE_ENTRY");
            long entryStamp = stage.getEnteredAt() != null
                    ? stage.getEnteredAt().toEpochMilli() : System.currentTimeMillis();
            String dedupeKey = applicationReference + ":" + stage.getStageKey() + ":" + entryStamp;
            CreateTaskRequest req = new CreateTaskRequest(
                    "WorkflowInstance", applicationReference, "STAGE_" + stage.getStageKey(),
                    stage.getQueueKey(), null, null, stage.getSlaHours(),
                    dedupeKey, "SYSTEM", null, payload);
            workItems.create(req, actor == null ? "system" : actor);
        } catch (Exception e) {
            log.warn("stage-entry task mirror skipped for {}/{} ({})",
                    applicationReference, stage.getStageKey(), e.getMessage());
        }
    }

    private void rollupSlaBreach(WorkflowInstance wf) {
        for (WorkflowStageState s : stages.findByInstanceIdOrderByOrdinalAsc(wf.getId())) {
            if (s.isSlaBreached()) { wf.setSlaBreached(true); return; }
        }
    }

    private String normalizeActorType(String raw, String actor) {
        if (raw == null || raw.isBlank()) {
            return (actor == null || actor.isBlank() || actor.equalsIgnoreCase("system"))
                    ? "SYSTEM" : "HUMAN";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!"HUMAN".equals(upper) && !"AI".equals(upper) && !"SYSTEM".equals(upper)) {
            throw ApiException.badRequest("actorType must be HUMAN, AI, or SYSTEM (got " + raw + ")");
        }
        return upper;
    }
}
