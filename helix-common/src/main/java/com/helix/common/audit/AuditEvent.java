package com.helix.common.audit;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only audit record (PRD §9). Once written, never updated or deleted.
 * Each service owns its own audit_events table in its own SQLite database;
 * together they form the platform's immutable trail.
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_subject", columnList = "subjectType,subjectId"),
        @Index(name = "idx_audit_event_type", columnList = "eventType"),
        @Index(name = "idx_audit_occurred", columnList = "occurredAt")
})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical service emitting the event (e.g. "risk-service"). */
    @Column(nullable = false, length = 60)
    private String service;

    /** Named actor responsible — a human user id, or an AI capability id. */
    @Column(nullable = false, length = 120)
    private String actor;

    /** Whether the actor was a human or an AI capability (governance signal). */
    @Column(nullable = false, length = 20)
    private String actorType;

    /** e.g. RATING_OVERRIDDEN, CAPITAL_COMPUTED, DECISION_RECORDED. */
    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(length = 60)
    private String subjectType;

    @Column(length = 80)
    private String subjectId;

    @Lob
    @Column(nullable = false, length = 2000)
    private String summary;

    @Lob
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> detail;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    public Long getId() {
        return id;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
