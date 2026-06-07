package com.helix.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * AI assessment of one line of a borrower-submitted covenant compliance
 * certificate (PRD §11, "automated covenant compliance status from
 * certificates"). Extracts the borrower-reported status + definition, maps it
 * to a system covenant, flags taxonomy mismatches, and recomputes the value
 * from the spreading module so a disagreement between the borrower's claim and
 * the deterministic recomputation is surfaced. Advisory + human-confirmed.
 */
@Entity
@Table(name = "certificate_assessments", indexes = {
        @Index(name = "idx_certassess_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CertificateAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Lob
    @Column(length = 1000)
    private String sourceLine;          // the certificate line this row came from

    // ---- borrower-reported (as written in the certificate) ----
    @Column(length = 60)
    private String reportedLabel;       // borrower's term, e.g. "Debt to EBITDA"
    private Double reportedValue;
    @Column(length = 20)
    private String reportedStatus;      // COMPLIED | NOT_COMPLIED | UNKNOWN

    // ---- system mapping ----
    private Long covenantId;            // linked covenant, when matched
    @Column(length = 40)
    private String systemMetric;        // canonical metric we track
    private boolean taxonomyMismatch;   // borrower's label ≠ our canonical definition

    // ---- deterministic recomputation from spreading ----
    private Double recomputedObserved;  // value from the spreading module
    private String operator;
    private Double threshold;
    private Boolean recomputedPassed;   // applying op/threshold to recomputedObserved

    /** True when the borrower-reported status matches the recomputation. */
    private Boolean agreement;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";    // DRAFT | CONFIRMED | REJECTED

    private boolean advisory = true;

    private String assessedBy;          // AI capability marker
    private String reviewedBy;
    private Instant reviewedAt;
    private String reviewNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
