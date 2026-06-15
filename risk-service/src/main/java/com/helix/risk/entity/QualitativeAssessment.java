package com.helix.risk.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One qualitative rating parameter's advisory assessment for a deal. The score is
 * RECOMMENDED by the AI qualitative engine (running the parameter's prompt from the
 * QUAL_SCORECARD master, grounded on deal data) and is <b>advisory</b> — it never
 * mutates the deterministic financial grade. A named human confirms (and may adjust)
 * each parameter; the prompt that produced it is persisted for traceability.
 */
@Entity
@Table(name = "qualitative_assessments", indexes = {
        @Index(name = "idx_qual_app", columnList = "applicationReference"),
        @Index(name = "idx_qual_app_status", columnList = "applicationReference,status")
})
@Getter
@Setter
public class QualitativeAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String parameterKey;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false)
    private double weight;

    /** AI-recommended score (0-100), grounded on deal data via the parameter prompt. */
    @Column(nullable = false)
    private double suggestedScore;

    /** Effective score: equals suggested until a human confirms (and optionally adjusts). */
    @Column(nullable = false)
    private double score;

    @Column(nullable = false, length = 12)
    private String band;            // STRONG | ADEQUATE | WEAK

    @Column(length = 1000)
    private String rationale;       // cites the deal data that drove the score

    /** The prompt that produced this assessment — persisted for full traceability. */
    @Column(length = 2000)
    private String prompt;

    @Column(length = 20)
    private String promptSource;    // SEED | MODEL_DOC

    @Column(nullable = false)
    private boolean advisory = true;

    @Column(nullable = false, length = 20)
    private String status = "SUGGESTED";   // SUGGESTED | CONFIRMED

    @Column(nullable = false, length = 40)
    private String suggestedBy = "qualitative-scorecard";   // AI capability

    @Column(length = 80) private String confirmedBy;
    private Instant confirmedAt;

    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
