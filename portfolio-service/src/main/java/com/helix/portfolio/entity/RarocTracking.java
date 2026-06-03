package com.helix.portfolio.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * Tracks projected (at origination) vs actual (realised over the life of the
 * facility) RAROC and the variance between them. The bank's capital engine
 * remains the system of record; here we maintain the deal-level pricing view to
 * close the loop on whether origination assumptions held up.
 */
@Entity
@Table(name = "raroc_tracking", indexes = {
        @Index(name = "idx_raroc_app", columnList = "applicationReference"),
        @Index(name = "idx_raroc_period", columnList = "applicationReference,periodLabel")
})
@Getter
@Setter
public class RarocTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 20)
    private String periodLabel;       // e.g. ORIGINATION, 2026Q2, FY2026

    /** True for the immutable snapshot taken at origination. */
    @Column(nullable = false)
    private boolean origination;

    private double projectedRaroc;
    private double projectedRecommendedRate;
    private double projectedExpectedLoss;
    private double projectedCapitalCharge;

    private double actualRaroc;
    private double actualIncome;
    private double actualExpectedLossRealised;
    private double actualCostOfFunds;
    private double actualOpex;

    private double variance;          // actual - projected
    private double absVariancePct;    // |variance| / projected (0..1)

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> drivers;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant computedAt;
}
