package com.helix.origination.entity;

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
import java.util.Map;

/**
 * Type-aware AI extraction over a collateral document — Property Title /
 * Valuation Report / Insurance / Vehicle / Bond / Guarantor (PRD §5 collateral).
 * Advisory: a human confirms each candidate, which materialises a real
 * {@link Collateral}. The deterministic figure path (capital projection's CRM
 * eligibility) is never touched by this module.
 */
@Entity
@Table(name = "collateral_extractions", indexes = {
        @Index(name = "idx_colext_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CollateralExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String collateralType;       // PROPERTY | VEHICLE | INSURANCE | TITLE_DEED | BOND | GUARANTOR

    @Column(nullable = false, length = 40)
    private String documentKind;         // VALUATION_REPORT | TITLE_DEED | INSURANCE_POLICY | VEHICLE_RC | BOND_CERT | PG_DEED

    // Raw collateral-document text (property/title/PG-deed): owner names, addresses,
    // guarantor personal details. Grounding-only, never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    @Lob
    @Column(length = 4000)
    private String sourceText;

    private double overallConfidence;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 6000)
    private Map<String, Object> fields;   // key -> {value, confidence}

    /** Mandatory fields the template expects but the parser couldn't find. */
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1500)
    private List<String> missingMandatory;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1500)
    private List<String> signals;

    @Column(nullable = false, length = 20)
    private String status = "SUGGESTED"; // SUGGESTED | CONFIRMED | REJECTED

    private boolean advisory = true;

    private String extractedBy;          // AI capability marker
    private String reviewedBy;
    private Instant reviewedAt;
    // Reviewer remark free text; never queried — encrypted at rest.
    @Convert(converter = EncryptedStringConverter.class)
    private String reviewNote;

    /** Set on confirm — the real collateral this extraction materialised into. */
    private Long linkedCollateralId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
