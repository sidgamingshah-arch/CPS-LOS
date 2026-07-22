package com.helix.decision.entity;

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
 * A CAM (Credit Appraisal Memorandum) annexure attached to a deal / proposal. ONE
 * master-driven engine backs every annexure type — CRI sheet, industry-scenario,
 * ESG assessment, exchange-risk, project-deferment, group-analysis — driven by the
 * {@code ANNEXURE_TYPE} master (config-as-data). A new annexure type is a new master
 * row, never a code change.
 *
 * <p>Annexures are advisory authoring artefacts: an author drafts the sections
 * (optionally with a governed AI draft at the LLM boundary), then the workflow routes
 * DRAFT → SUBMITTED → REVIEWED → APPROVED (or → REJECTED with a reason) with
 * maker-checker SoD (reviewer / approver ≠ author). It NEVER mutates an authoritative
 * figure — the subject application's grade / PD / spread live upstream and are
 * untouched here.</p>
 *
 * <p>The section content is a JSON map materialised from the master's section template
 * at create-time; {@code typeVersion} pins the {@code ANNEXURE_TYPE} master version used
 * so a later master edit never silently reshapes an in-flight annexure.</p>
 */
@Entity
@Table(name = "annexures", indexes = {
        @Index(name = "uq_annexure_ref", columnList = "annexureRef", unique = true),
        @Index(name = "idx_annexure_subject", columnList = "subjectRef"),
        @Index(name = "idx_annexure_status", columnList = "status"),
        @Index(name = "idx_annexure_type", columnList = "annexureType")
})
@Getter
@Setter
public class Annexure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code ANX-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String annexureRef;

    /** The ANNEXURE_TYPE master key (CRI_SHEET / INDUSTRY_SCENARIO / ESG_ASSESSMENT / …). */
    @Column(nullable = false, length = 60)
    private String annexureType;

    /** What the annexure is about — typically APPLICATION / DEAL / PROPOSAL (free text). */
    @Column(length = 60)
    private String subjectType;

    /** The subject reference — the deal / application reference the annexure hangs off. */
    @Column(length = 120)
    private String subjectRef;

    @Column(length = 240)
    private String title;

    /** Section content, materialised from the master's section template at create-time. */
    @Lob
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 16000)
    private Map<String, Object> sections;

    /** Version of the ANNEXURE_TYPE master pinned when this annexure was materialised. */
    @Column(nullable = false)
    private int typeVersion;

    /** Advisory authoring artefact — never a source of authoritative figures. */
    @Column(nullable = false)
    private boolean advisory = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnnexureStatus status = AnnexureStatus.DRAFT;

    /** The named human who created / authors the annexure (X-Actor at create-time). */
    @Column(nullable = false, length = 120)
    private String author;

    @Column(length = 120)
    private String reviewer;

    // ---- workflow decision fields ----
    private Instant submittedAt;

    private String reviewedBy;
    private Instant reviewedAt;
    @Lob
    @Column(length = 4000)
    private String reviewNotes;

    private String approvedBy;
    private Instant approvedAt;

    private String rejectedBy;
    private Instant rejectedAt;
    @Lob
    @Column(length = 4000)
    private String rejectReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
