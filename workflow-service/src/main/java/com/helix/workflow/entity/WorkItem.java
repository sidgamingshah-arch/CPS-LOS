package com.helix.workflow.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Map;

/**
 * A case-management work-item — a <b>task mirror</b> over the authoritative domain
 * status machines. It never approves anything and holds no credit figures: it only
 * records "someone must do X for subject Y" and the timeline of who did it (TAT).
 *
 * <p>Idempotent on ({@code subjectType},{@code subjectRef},{@code taskType},
 * {@code dedupeKey}) — re-creating the same logical task returns the existing one
 * (see {@code WorkItemRepository.findFirstBySubjectType...}). Reserved-word-safe
 * column names throughout ({@code task_ref}, {@code queue_key}, {@code join_group_id},
 * … — SQLite bites on {@code key}/{@code index}/{@code order}).</p>
 */
@Entity
@Table(name = "work_items", indexes = {
        @Index(name = "idx_wi_task_ref", columnList = "task_ref", unique = true),
        @Index(name = "idx_wi_dedupe", columnList = "subjectType,subjectRef,taskType,dedupe_key"),
        @Index(name = "idx_wi_queue", columnList = "queue_key,status"),
        @Index(name = "idx_wi_assignee", columnList = "assignee,status"),
        @Index(name = "idx_wi_join", columnList = "join_group_id"),
        @Index(name = "idx_wi_subject", columnList = "subjectType,subjectRef")
})
@Getter
@Setter
public class WorkItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_ref", nullable = false, unique = true, length = 20)
    private String taskRef;   // TSK-XXXXXX

    @Column(nullable = false, length = 40)
    private String subjectType;

    @Column(nullable = false, length = 60)
    private String subjectRef;

    @Column(nullable = false, length = 60)
    private String taskType;

    @Column(name = "queue_key", length = 60)
    private String queueKey;

    @Column(length = 80)
    private String assignee;   // nullable — unclaimed tasks sit in the queue

    /** OPEN | ASSIGNED | COMPLETED | SENT_BACK | WITHDRAWN | CANCELLED. */
    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    private int priority = 5;

    @Column(name = "sla_hours")
    private Integer slaHours;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "sla_breached")
    private boolean slaBreached;

    @Column(name = "rework_cycle", nullable = false)
    private int reworkCycle = 0;

    /** Fan-out group id shared across sibling tasks; nullable for standalone tasks. */
    @Column(name = "join_group_id", length = 20)
    private String joinGroupId;

    /** ALL | ANY | QUORUM:n — how many siblings must complete to satisfy the group. */
    @Column(name = "join_policy", length = 20)
    private String joinPolicy;

    /** Normalised idempotency discriminator (blank → ""). */
    @Column(name = "dedupe_key", length = 80)
    private String dedupeKey = "";

    /** Who created the task — used to route a send-back rework back to the originator. */
    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> payload = Map.of();

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
