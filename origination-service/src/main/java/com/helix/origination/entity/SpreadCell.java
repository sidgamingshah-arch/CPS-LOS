package com.helix.origination.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A single canonical spread figure with full provenance (PRD §4, US-4.1):
 * every cell links to its source document/page/coordinates, the extracted value,
 * and a confidence. Both the original and any analyst override are retained.
 */
@Entity
@Table(name = "spread_cells", indexes = {
        @Index(name = "idx_cell_period", columnList = "periodId"),
        @Index(name = "idx_cell_application", columnList = "applicationId")
})
@Getter
@Setter
public class SpreadCell {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long periodId;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false, length = 40)
    private String taxonomyKey;        // REVENUE, EBITDA, TOTAL_DEBT, ...

    @Column(nullable = false)
    private String label;

    /** Whether this line was extracted/input or derived from other canonical lines. */
    @Column(nullable = false)
    private boolean derived;

    /** Effective value used downstream (= override when present, else extracted). */
    @Column(nullable = false)
    private double value;

    private double extractedValue;
    private double confidence;

    // ---- provenance (PRD §4) ----
    private String sourceDocument;
    private String sourcePage;
    private String sourceCoordinates;

    // ---- override audit (original always retained) ----
    private boolean overridden;
    private Double overrideValue;
    private String overrideReason;
    private boolean materialOverride;
    private String overriddenBy;
}
