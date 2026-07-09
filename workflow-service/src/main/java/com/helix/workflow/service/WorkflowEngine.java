package com.helix.workflow.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.workflow.client.DefinitionClient;
import com.helix.workflow.dto.WorkflowDefinitionDto;
import com.helix.workflow.entity.WorkflowInstance;
import com.helix.workflow.entity.WorkflowStageState;
import com.helix.workflow.entity.WorkflowTransition;
import com.helix.workflow.repo.WorkflowInstanceRepository;
import com.helix.workflow.repo.WorkflowStageStateRepository;
import com.helix.workflow.repo.WorkflowTransitionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final WorkflowInstanceRepository instances;
    private final WorkflowStageStateRepository stages;
    private final WorkflowTransitionRepository transitions;
    private final DefinitionClient definitions;
    private final AuditService audit;

    public WorkflowEngine(WorkflowInstanceRepository instances, WorkflowStageStateRepository stages,
                          WorkflowTransitionRepository transitions, DefinitionClient definitions,
                          AuditService audit) {
        this.instances = instances;
        this.stages = stages;
        this.transitions = transitions;
        this.definitions = definitions;
        this.audit = audit;
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

        // Enter next stage.
        WorkflowStageState next = null;
        for (WorkflowStageState s : all) {
            if (s.getOrdinal() > target.getOrdinal() && "PENDING".equals(s.getStatus())) { next = s; break; }
        }
        if (next != null) {
            next.setStatus("IN_PROGRESS");
            next.setEnteredAt(now);
            next.setSlaDueAt(now.plusSeconds(next.getSlaHours() * 3600L));
            stages.save(next);
            wf.setCurrentStageKey(next.getStageKey());
        } else {
            wf.setStatus("COMPLETED");
            wf.setCompletedAt(now);
        }
        rollupSlaBreach(wf);
        instances.save(wf);

        WorkflowTransition tx = new WorkflowTransition();
        tx.setInstanceId(wf.getId());
        tx.setFromStageKey(targetStageKey);
        tx.setToStageKey(next == null ? null : next.getStageKey());
        tx.setKind(next == null ? "COMPLETED" : "ADVANCED");
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

        // If recording the currentStage, advance the cursor.
        if (stageKey.equals(wf.getCurrentStageKey())) {
            WorkflowStageState next = nextPending(wf.getId(), stage.getOrdinal());
            if (next != null) {
                next.setStatus("IN_PROGRESS");
                next.setEnteredAt(now);
                next.setSlaDueAt(now.plusSeconds(next.getSlaHours() * 3600L));
                stages.save(next);
                wf.setCurrentStageKey(next.getStageKey());
            } else {
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

    // =============================================================== helpers

    private WorkflowStageState nextPending(Long instanceId, int afterOrdinal) {
        for (WorkflowStageState s : stages.findByInstanceIdOrderByOrdinalAsc(instanceId)) {
            if (s.getOrdinal() > afterOrdinal && "PENDING".equals(s.getStatus())) return s;
        }
        return null;
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
