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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One row per application lifecycle. Pins the WORKFLOW_DEFINITION
 * pack version that was active at materialisation — edits to the pack do not
 * retro-rewrite in-flight instances.
 */
@Entity
@Table(name = "workflow_instances", indexes = {
        @Index(name = "idx_wf_app", columnList = "applicationReference", unique = true),
        @Index(name = "idx_wf_status", columnList = "status")
})
@Getter
@Setter
public class WorkflowInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String applicationReference;

    @Column(length = 80)
    private String definitionCode;     // e.g. workflow_mid_corp_rbi_v1

    private Integer definitionVersion;

    @Column(length = 20)
    private String jurisdiction;

    @Column(length = 40)
    private String segment;

    /** {@code stageKey} of the stage currently IN_PROGRESS (or last completed if instance complete). */
    @Column(length = 40)
    private String currentStageKey;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";   // ACTIVE | COMPLETED | ABANDONED

    private Instant startedAt;
    private Instant completedAt;

    /** Rolled up from any stage past its slaDueAt. */
    private boolean slaBreached;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
