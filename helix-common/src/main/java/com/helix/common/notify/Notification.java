package com.helix.common.notify;

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
import java.util.List;
import java.util.Map;

/**
 * A durable, idempotent outbound notification (the deliverable side-effect the platform
 * was missing behind {@code EMAIL_TEMPLATE}). Each service owns its own {@code notifications}
 * table in its own SQLite database — like {@link com.helix.common.audit.AuditEvent} — and
 * the default {@code OUTBOX} transport records only: the persisted, template-rendered row
 * IS the deliverable (a real SMTP/webhook transport is a drop-in behind the same interface).
 * The row is keyed by {@code idempotencyKey} so re-firing a sweep never duplicates.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "uq_notify_idem", columnList = "idempotencyKey", unique = true),
        @Index(name = "idx_notify_subject", columnList = "subjectType,subjectRef"),
        @Index(name = "idx_notify_event", columnList = "eventType"),
        @Index(name = "idx_notify_status", columnList = "status")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String channel = "EMAIL";

    @Column(length = 60)
    private String subjectType;

    @Column(length = 120)
    private String subjectRef;

    @Column(length = 200)
    private String dedupeKey;

    @Column(nullable = false, length = 300)
    private String idempotencyKey;

    @Column(length = 80)
    private String templateKey;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> recipientRoles;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> recipients;

    @Lob
    @Column(length = 2000)
    private String renderedSubject;

    @Lob
    @Column(length = 8000)
    private String renderedBody;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> vars;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";      // PENDING | SCHEDULED | SENT | FAILED | SUPPRESSED

    @Column(nullable = false, length = 20)
    private String transport = "OUTBOX";

    @Column(length = 200)
    private String providerRef;

    @Column(length = 500)
    private String failureReason;

    @Column(length = 120)
    private String createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;

    /** When set (status SCHEDULED), transport dispatch is deferred until the sweep passes this instant. */
    private Instant scheduledFor;

    /** Reminder cadence in hours (0 = every sweep); null ⇒ not reminder-eligible. */
    private Integer reminderEveryHours;

    /** Cap on reminder rows spawned from this notification; null ⇒ not reminder-eligible. */
    private Integer maxReminders;

    /** How many reminder rows have been spawned so far (null means 0). */
    private Integer remindersSent;

    private Instant lastReminderAt;

    public Long getId() { return id; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }

    public String getSubjectRef() { return subjectRef; }
    public void setSubjectRef(String subjectRef) { this.subjectRef = subjectRef; }

    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }

    public List<String> getRecipientRoles() { return recipientRoles; }
    public void setRecipientRoles(List<String> recipientRoles) { this.recipientRoles = recipientRoles; }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients; }

    public String getRenderedSubject() { return renderedSubject; }
    public void setRenderedSubject(String renderedSubject) { this.renderedSubject = renderedSubject; }

    public String getRenderedBody() { return renderedBody; }
    public void setRenderedBody(String renderedBody) { this.renderedBody = renderedBody; }

    public Map<String, Object> getVars() { return vars; }
    public void setVars(Map<String, Object> vars) { this.vars = vars; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public String getProviderRef() { return providerRef; }
    public void setProviderRef(String providerRef) { this.providerRef = providerRef; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public Instant getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(Instant scheduledFor) { this.scheduledFor = scheduledFor; }

    public Integer getReminderEveryHours() { return reminderEveryHours; }
    public void setReminderEveryHours(Integer reminderEveryHours) { this.reminderEveryHours = reminderEveryHours; }

    public Integer getMaxReminders() { return maxReminders; }
    public void setMaxReminders(Integer maxReminders) { this.maxReminders = maxReminders; }

    public Integer getRemindersSent() { return remindersSent; }
    public void setRemindersSent(Integer remindersSent) { this.remindersSent = remindersSent; }

    public Instant getLastReminderAt() { return lastReminderAt; }
    public void setLastReminderAt(Instant lastReminderAt) { this.lastReminderAt = lastReminderAt; }
}
