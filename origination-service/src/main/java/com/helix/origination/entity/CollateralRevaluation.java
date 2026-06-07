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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One revaluation of a collateral — captures the previous and new market value,
 * the LTV that results, and whether the LTV breaches the configured threshold.
 * Advisory; the human still confirms whether to overwrite the live collateral
 * value. Alerts are stamped as {@code audit.ai("collateral-monitor", ...)} so
 * the trail records the source of the LTV change.
 */
@Entity
@Table(name = "collateral_revaluations", indexes = {
        @Index(name = "idx_reval_collateral", columnList = "collateralId")
})
@Getter
@Setter
public class CollateralRevaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long collateralId;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    private double previousMarketValue;
    private double newMarketValue;
    private double drawnExposure;       // observed at the time of revaluation
    private double ltvBefore;
    private double ltvAfter;

    @Column(nullable = false, length = 30)
    private String trigger;             // VALUATION_UPDATE | FX_REVAL | EXPOSURE_INCREASE | PERIODIC

    private LocalDate effectiveDate;

    /** True when the post-revaluation LTV exceeds the configured threshold. */
    private boolean ltvBreached;
    private double ltvThreshold;
    private String alertSeverity;       // INFO | WARN | BREACH

    private String note;
    private String triggeredBy;         // actor or "system"

    /** Whether a human accepted the new MV onto the live collateral row. */
    @Column(nullable = false, length = 20)
    private String confirmStatus = "PENDING"; // PENDING | APPLIED | REJECTED

    private String reviewedBy;
    private Instant reviewedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
