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
}
