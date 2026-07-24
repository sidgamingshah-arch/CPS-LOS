package com.helix.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One row per (instance, stage). Materialised at instance creation from the
 * pinned WORKFLOW_DEFINITION payload — `key` is a SQLite reserved word, so we
 * name the column {@code stage_key} (matches the existing platform fix for
 * {@code primary}/{@code limit}).
 */
@Entity
@Table(name = "workflow_stage_states", indexes = {
        @Index(name = "idx_wf_stage_instance", columnList = "instanceId,ordinal", unique = true),
        @Index(name = "idx_wf_stage_status", columnList = "status"),
        @Index(name = "idx_wf_stage_sla", columnList = "slaBreached")
})
@Getter
@Setter
public class WorkflowStageState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "stage_key", nullable = false, length = 40)
    private String stageKey;

    @Column(nullable = false, length = 120)
    private String label;

    /** AI autonomy level — A=autonomous, C=consulting, D=decision-gate, — none. */
    @Column(length = 4)
    private String autonomy;

    private boolean aiAllowed;

    /** When true, only a HUMAN actor may complete this stage. */
    private boolean humanGate;

    private int slaHours;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";    // PENDING | IN_PROGRESS | COMPLETE | BLOCKED | SKIPPED

    private Instant enteredAt;
    private Instant completedAt;

    @Column(length = 80)
    private String completedBy;

    /** {@code HUMAN} | {@code AI} | {@code SYSTEM} — must respect {@link #humanGate}/{@link #autonomy}. */
    @Column(length = 10)
    private String completedByType;

    @Column(length = 400)
    private String note;

    /** Computed at {@link #enteredAt} + slaHours. */
    private Instant slaDueAt;

    private boolean slaBreached;

    @Column(length = 400)
    private String blockedReason;

    /**
     * OPTIONAL parallel-stage-group key (pack stage key {@code parallelGroup}). When
     * null (all existing seeded packs) the engine behaves as a strict linear
     * lifecycle. Stages sharing a group are co-entered and the group advances only
     * when its {@link #joinPolicy} is satisfied.
     */
    @Column(name = "parallel_group", length = 40)
    private String parallelGroup;

    /** OPTIONAL join policy for the parallel group — ALL | ANY | QUORUM:n (default ALL). */
    @Column(name = "join_policy", length = 20)
    private String joinPolicy;

    /**
     * OPTIONAL queue key (pack stage key {@code queueKey}). When present, entering
     * this stage auto-creates a case-management WorkItem (best-effort mirror).
     */
    @Column(name = "queue_key", length = 60)
    private String queueKey;

    /**
     * OPTIONAL auto-advance window in hours (pack stage key {@code autoAdvanceAfterHours}).
     * When non-null AND the stage has dwelt past it, the auto-movement sweep completes this
     * stage as SYSTEM and enters the next. NULL for every existing seeded pack, so the engine
     * behaves byte-identically when the key is absent. A human-gated ({@code humanGate=true})
     * or decision-gate ({@code autonomy=D}) stage is NEVER auto-advanced even if it declares
     * the key — a human still owns that transition.
     */
    @Column(name = "auto_advance_after_hours")
    private Integer autoAdvanceAfterHours;

    /**
     * OPTIONAL auto-lapse window in hours (pack stage key {@code autoLapseAfterHours}). When
     * non-null AND the stage has dwelt past it, the auto-movement sweep lapses the WHOLE
     * instance to {@link #autoLapseToStatus}. NULL for every existing seeded pack.
     */
    @Column(name = "auto_lapse_after_hours")
    private Integer autoLapseAfterHours;

    /**
     * OPTIONAL terminal status an auto-lapse moves the instance to (pack stage key
     * {@code autoLapseToStatus}); defaults to {@code LAPSED} at sweep time when null.
     */
    @Column(name = "auto_lapse_to_status", length = 20)
    private String autoLapseToStatus;
}
