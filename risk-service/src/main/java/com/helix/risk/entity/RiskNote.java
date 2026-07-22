package com.helix.risk.entity;

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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * An <b>Independent Risk Note</b> (CLoM R1-13) — the risk function's own narrative
 * opinion on a credit, with a governed approval lifecycle (draft → submit → review →
 * approve, plus reassign / reject / reverse).
 *
 * <p>This is deliberately <i>distinct</i> from the advisory {@link RagAssessment}
 * statistical overlay: a RAG band is a computed 0–100 score; this is a human-authored
 * (optionally AI-drafted) qualitative opinion record. Like every advisory artefact in
 * the platform it is marked {@code advisory = true} and it <b>never mutates the
 * authoritative {@link Rating}</b> — it forms and records an opinion <em>about</em> the
 * rating, quoting a snapshot of the grade/PD, but writes nothing back to it.</p>
 */
@Entity
@Table(name = "risk_notes", indexes = {
        @Index(name = "idx_risknote_ref", columnList = "riskNoteRef", unique = true),
        @Index(name = "idx_risknote_subject", columnList = "subjectRef"),
        @Index(name = "idx_risknote_status", columnList = "status")
})
@Getter
@Setter
public class RiskNote {

    /** Lifecycle of an independent risk note. Persisted as a String (never an ordinal). */
    public enum Status {
        DRAFT, SUBMITTED, REVIEWED, APPROVED, REJECTED, REVERSED
    }

    /** The risk function's headline recommendation. */
    public enum RecommendedAction {
        SUPPORT, SUPPORT_WITH_CONDITIONS, DECLINE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String riskNoteRef;              // RN-XXXXXX

    @Column(nullable = false, length = 60)
    private String subjectRef;               // application reference the opinion is about

    /** Narrative sections keyed RISK_OPINION / KEY_RISKS / MITIGANTS / RECOMMENDATION. */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 16000)
    private Map<String, Object> sections;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RecommendedAction recommendedAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false, length = 60)
    private String author;                   // named human who raised the note

    @Column(length = 60)
    private String reviewer;                 // named human who reviewed / approved (SoD: != author)

    @Column(length = 60)
    private String assignedTo;               // current owner of the work item

    @Column(nullable = false)
    private boolean advisory;                // always true — an opinion record, never a figure

    // ---- authoritative rating snapshot at authoring time (READ-ONLY context, never written back) ----
    @Column(length = 5)
    private String gradeSnapshot;
    private double pdSnapshot;

    // ---- lifecycle stamps ----
    private Instant submittedAt;
    private Instant reviewedAt;
    private Instant decidedAt;               // approve / reject
    private Instant reversedAt;

    @Column(length = 2000)
    private String decisionNote;             // approver note / rejection reason / reversal reason

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
