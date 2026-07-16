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

/**
 * Append-only timeline for a {@link WorkItem} — this IS the turn-around-time (TAT)
 * record. Every write on the task appends one row; nothing is ever updated or
 * deleted (mirrors the platform audit table).
 */
@Entity
@Table(name = "work_item_events", indexes = {
        @Index(name = "idx_wie_ref", columnList = "work_item_ref,id")
})
@Getter
@Setter
public class WorkItemEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_item_ref", nullable = false, length = 20)
    private String workItemRef;

    /** CREATED | ASSIGNED | CLAIMED | COMPLETED | SENT_BACK | WITHDRAWN | REASSIGNED. */
    @Column(nullable = false, length = 20)
    private String event;

    @Column(length = 80)
    private String actor;

    @Column(length = 10)
    private String actorType;   // HUMAN | AI | SYSTEM

    @Column(length = 400)
    private String note;

    @CreationTimestamp
    @Column(name = "occurred_at", updatable = false)
    private Instant at;
}
