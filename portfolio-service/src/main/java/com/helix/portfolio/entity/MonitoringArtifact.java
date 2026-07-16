package com.helix.portfolio.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * A post-disbursement monitoring artifact. ONE engine backs every monitoring
 * artifact type — call memo, plant/site visit, LCR (loan-compliance review),
 * QPR (quarterly performance review), broker review, stock audit, audit note —
 * driven by the {@code MONITORING_ARTIFACT_TYPE} master (config-as-data). A new
 * type is a new master row, never a code change.
 *
 * <p>Artifacts are records / advisory: the workflow gathers monitoring evidence
 * and routes it through DRAFT → SUBMITTED → REVIEWED → APPROVED (→ AUTHORIZED).
 * It NEVER mutates an authoritative figure (ECL / IRAC / exposure) — those live
 * on {@link EclResult} / {@link ExposureRecord} and are untouched here.</p>
 *
 * <p>Column names are reserved-word-safe (no bare {@code primary}, {@code limit},
 * {@code order}). The section content is a JSON map materialised from the master's
 * section template at create-time; {@code masterVersion} pins the version used.</p>
 */
@Entity
@Table(name = "monitoring_artifacts", indexes = {
        @Index(name = "uq_mon_artifact_ref", columnList = "artifactRef", unique = true),
        @Index(name = "idx_mon_subject", columnList = "subjectRef"),
        @Index(name = "idx_mon_status", columnList = "status"),
        @Index(name = "idx_mon_type", columnList = "artifactType")
})
@Getter
@Setter
public class MonitoringArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code MON-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String artifactRef;

    /** The MONITORING_ARTIFACT_TYPE master key (CALL_MEMO / PLANT_VISIT / LCR / …). */
    @Column(nullable = false, length = 60)
    private String artifactType;

    /** What the artifact is about — OBLIGOR / FACILITY / EXPOSURE (free text). */
    @Column(length = 60)
    private String subjectType;

    @Column(length = 120)
    private String subjectRef;

    @Column(length = 240)
    private String title;

    /** Section content, materialised from the master's section template at create-time. */
    @Lob
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 16000)
    private Map<String, Object> sections;

    /** Version of the MONITORING_ARTIFACT_TYPE master used to materialise this artifact. */
    @Column(nullable = false)
    private int masterVersion;

    /** Pinned from the master at create-time — drives whether AUTHORIZED is reachable. */
    @Column(nullable = false)
    private boolean requiresAuthorize;

    /** Pinned from the master at create-time — enables the vendor-RFQ lane (stock audit). */
    @Column(nullable = false)
    private boolean vendorRfq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MonitoringArtifactStatus status = MonitoringArtifactStatus.DRAFT;

    @Column(nullable = false, length = 120)
    private String owner;

    @Column(length = 120)
    private String reviewer;

    @Column(length = 120)
    private String approver;

    // ---- workflow decision fields ----
    private Instant submittedAt;

    private String reviewedBy;
    private Instant reviewedAt;
    @Lob
    @Column(length = 4000)
    private String reviewNotes;

    private String approvedBy;
    private Instant approvedAt;
    @Lob
    @Column(length = 4000)
    private String approvalNotes;

    private String authorisedBy;
    private Instant authorisedAt;
    @Lob
    @Column(length = 4000)
    private String authorisationNotes;

    // ---- vendor RFQ (stock audit) ----
    /** Chosen vendor id (a VENDOR_MASTER recordKey) — set by a human, never auto-selected. */
    @Column(length = 120)
    private String vendorRef;

    /** The EXTERNAL_VENDOR query thread raised for the RFQ ({@code QRY-XXXXXX}). */
    @Column(length = 40)
    private String vendorQueryRef;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
