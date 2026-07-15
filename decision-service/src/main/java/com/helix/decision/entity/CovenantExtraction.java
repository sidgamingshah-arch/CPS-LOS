package com.helix.decision.entity;

import com.helix.common.util.EncryptedStringConverter;
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
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;

/**
 * An AI-extracted covenant candidate parsed from credit-proposal free text
 * (PRD §7, "extraction of covenant definitions and thresholds"). Advisory and
 * never auto-applied: a human confirms it, which materialises a real
 * {@link Covenant} and stamps {@code audit.human}. The extractor stamps
 * {@code audit.ai("covenant-extraction", ...)}.
 */
@Entity
@Table(name = "covenant_extractions", indexes = {
        @Index(name = "idx_covext_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CovenantExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    /** The clause/sentence the candidate was lifted from (grounding). */
    // Credit-proposal free text (may name parties/guarantors). Grounding-only, the
    // confirm path materialises from the parsed shape fields, never from this text;
    // never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    @Column(length = 2000)
    private String sourceText;

    // ---- extracted, editable-before-confirm covenant shape ----
    @Column(length = 40)
    private String covenantType;       // FINANCIAL_MAINTENANCE | INFORMATION | NEGATIVE_PLEDGE
    @Column(length = 40)
    private String metric;             // canonical token (DSCR, NET_LEVERAGE, ...)
    @Column(length = 60)
    private String reportedLabel;      // the raw phrase the borrower used ("Debt/EBITDA")
    @Column(length = 5)
    private String operator;           // >=, <=, >, <, ==
    private Double threshold;
    @Column(length = 20)
    private String testFrequency;      // MONTHLY | QUARTERLY | HALF_YEARLY | ANNUAL
    @Column(length = 20)
    private String breachSeverity;     // MINOR | MAJOR | CRITICAL

    private double confidence;

    /** Human-readable reasoning for why this candidate was produced. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1500)
    private List<String> signals;

    /** Set when the extractor couldn't fully resolve the candidate. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> gaps;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";   // DRAFT | CONFIRMED | REJECTED

    private boolean advisory = true;

    private String extractedBy;        // AI capability marker
    private String reviewedBy;
    private Instant reviewedAt;
    // Reviewer remark free text; never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    private String reviewNote;

    /** Set on confirm — the real covenant this extraction materialised into. */
    private Long linkedCovenantId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
