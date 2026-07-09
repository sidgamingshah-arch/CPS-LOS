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

import java.time.Instant;

/** Append-only — every advance/record/block writes one row. Mirrors the audit table pattern. */
@Entity
@Table(name = "workflow_transitions", indexes = {
        @Index(name = "idx_wf_tx_instance", columnList = "instanceId,id")
})
@Getter
@Setter
public class WorkflowTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long instanceId;

    @Column(length = 40)
    private String fromStageKey;

    @Column(length = 40)
    private String toStageKey;

    @Column(length = 20)
    private String kind;   // ADVANCED | RECORDED | BLOCKED | UNBLOCKED | MATERIALISED | COMPLETED

    @Column(length = 80)
    private String actor;

    @Column(length = 10)
    private String actorType;   // HUMAN | AI | SYSTEM

    @Column(length = 400)
    private String note;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant occurredAt;
}
