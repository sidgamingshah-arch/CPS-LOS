package com.helix.common.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A single query / RFI collaboration thread. Automatically present in every service that
 * includes helix-common (like {@link com.helix.common.audit.AuditEvent} and
 * {@link com.helix.common.notify.Notification}) — each service owns its own
 * {@code query_threads} table in its own SQLite database.
 *
 * <p>Column names are deliberately reserved-word-safe (there is no bare {@code query}, {@code
 * limit}, {@code order}, … column). The append-only conversation lives in {@link QueryMessage}.</p>
 */
@Entity
@Table(name = "query_threads", indexes = {
        @Index(name = "uq_query_ref", columnList = "queryRef", unique = true),
        @Index(name = "idx_query_subject", columnList = "subjectType,subjectRef"),
        @Index(name = "idx_query_addressee", columnList = "addressee"),
        @Index(name = "idx_query_status", columnList = "status")
})
public class QueryThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code QRY-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String queryRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueryChannel channel;

    @Column(length = 60)
    private String subjectType;

    @Column(length = 120)
    private String subjectRef;

    @Column(length = 200)
    private String topic;

    @Lob
    @Column(length = 4000)
    private String question;

    /** Named human (or system) that raised the thread — persisted from {@code X-Actor}. */
    @Column(nullable = false, length = 120)
    private String raisedBy;

    /** Optional named addressee (internal user) the thread is directed at. */
    @Column(length = 120)
    private String addressee;

    /** Optional role the thread is directed at (drives notification recipient roles). */
    @Column(length = 80)
    private String addresseeRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QueryStatus status = QueryStatus.OPEN;

    /** Optional SLA in hours; when set, {@link #dueAt} is computed at raise/dispatch time. */
    private Integer slaHours;

    private Instant dueAt;

    /** When set (status SCHEDULED), the platform sweep releases the thread once this instant passes. */
    private Instant scheduleAt;

    /** Reminder cadence in hours for the RFI notification (0 = every sweep); null ⇒ no reminders. */
    private Integer reminderEveryHours;

    /** Cap on reminder rows spawned for the RFI notification; null ⇒ no reminders. */
    private Integer maxReminders;

    /** How many reminders have been configured/spawned (the notification lane owns the real count). */
    private Integer remindersSent = 0;

    @Column(length = 120)
    private String resolvedBy;

    private Instant resolvedAt;

    @Lob
    @Column(length = 4000)
    private String resolution;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public String getQueryRef() { return queryRef; }
    public void setQueryRef(String queryRef) { this.queryRef = queryRef; }

    public QueryChannel getChannel() { return channel; }
    public void setChannel(QueryChannel channel) { this.channel = channel; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public String getSubjectRef() { return subjectRef; }
    public void setSubjectRef(String subjectRef) { this.subjectRef = subjectRef; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getRaisedBy() { return raisedBy; }
    public void setRaisedBy(String raisedBy) { this.raisedBy = raisedBy; }

    public String getAddressee() { return addressee; }
    public void setAddressee(String addressee) { this.addressee = addressee; }

    public String getAddresseeRole() { return addresseeRole; }
    public void setAddresseeRole(String addresseeRole) { this.addresseeRole = addresseeRole; }

    public QueryStatus getStatus() { return status; }
    public void setStatus(QueryStatus status) { this.status = status; }

    public Integer getSlaHours() { return slaHours; }
    public void setSlaHours(Integer slaHours) { this.slaHours = slaHours; }

    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }

    public Instant getScheduleAt() { return scheduleAt; }
    public void setScheduleAt(Instant scheduleAt) { this.scheduleAt = scheduleAt; }

    public Integer getReminderEveryHours() { return reminderEveryHours; }
    public void setReminderEveryHours(Integer reminderEveryHours) { this.reminderEveryHours = reminderEveryHours; }

    public Integer getMaxReminders() { return maxReminders; }
    public void setMaxReminders(Integer maxReminders) { this.maxReminders = maxReminders; }

    public Integer getRemindersSent() { return remindersSent; }
    public void setRemindersSent(Integer remindersSent) { this.remindersSent = remindersSent; }

    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
