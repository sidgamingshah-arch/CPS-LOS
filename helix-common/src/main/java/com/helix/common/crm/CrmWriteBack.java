package com.helix.common.crm;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * A CRM write-back record — the symmetric counterpart, for the CRM destination, of the
 * downstream {@code ExportBatch}. Holds the full canonical {@link com.helix.common.export.Export.Envelope}
 * as JSON and is idempotent on {@code idempotencyKey} (case/subject + as-of day), so re-triggering the
 * same write-back for the same day returns the existing row rather than re-pushing. In {@code simulated}
 * mode the row itself is the deliverable; in {@code live} mode it records the outcome of the real POST.
 *
 * <p>Manual accessors (not Lombok): helix-common entities do not carry Lombok on their classpath.</p>
 */
@Entity
@Table(name = "crm_write_back",
        uniqueConstraints = @UniqueConstraint(name = "uq_crm_idem", columnNames = "idempotencyKey"),
        indexes = {
                @Index(name = "idx_crm_subject", columnList = "subjectRef"),
                @Index(name = "idx_crm_key", columnList = "idempotencyKey")
        })
public class CrmWriteBack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String destination;          // always CRM

    @Column(nullable = false, length = 60)
    private String feedType;             // CASE_STATUS

    @Column(nullable = false, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    private String asOf;

    @Column(length = 60)
    private String subjectType;

    @Column(length = 120)
    private String subjectRef;

    @Column(length = 120)
    private String caseRef;

    @Column(length = 60)
    private String stage;

    @Column(name = "case_status", length = 60)
    private String caseStatus;           // the authoritative case/decision status pushed

    @Column(nullable = false, length = 20)
    private String mode;                 // SIMULATED | LIVE

    @Column(name = "delivery_status", nullable = false, length = 20)
    private String deliveryStatus;       // SIMULATED | DELIVERED | FAILED

    @Column(length = 200)
    private String providerRef;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private int recordCount;

    @Column(length = 120)
    private String generatedBy;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 100000)
    private Map<String, Object> envelope;   // the full canonical Export.Envelope

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getFeedType() {
        return feedType;
    }

    public void setFeedType(String feedType) {
        this.feedType = feedType;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getAsOf() {
        return asOf;
    }

    public void setAsOf(String asOf) {
        this.asOf = asOf;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getSubjectRef() {
        return subjectRef;
    }

    public void setSubjectRef(String subjectRef) {
        this.subjectRef = subjectRef;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public void setCaseRef(String caseRef) {
        this.caseRef = caseRef;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getCaseStatus() {
        return caseStatus;
    }

    public void setCaseStatus(String caseStatus) {
        this.caseStatus = caseStatus;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(int recordCount) {
        this.recordCount = recordCount;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public void setGeneratedBy(String generatedBy) {
        this.generatedBy = generatedBy;
    }

    public Map<String, Object> getEnvelope() {
        return envelope;
    }

    public void setEnvelope(Map<String, Object> envelope) {
        this.envelope = envelope;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
