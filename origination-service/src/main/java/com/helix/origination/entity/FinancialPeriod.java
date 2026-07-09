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

/** One reporting period of a spread (e.g. FY2024), under a stated GAAP. */
@Entity
@Table(name = "financial_periods", indexes = {
        @Index(name = "idx_period_application", columnList = "applicationId")
})
@Getter
@Setter
public class FinancialPeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false, length = 20)
    private String label;              // FY2024

    @Column(nullable = false, length = 20)
    private String gaap;               // IND_AS | IFRS | LOCAL_GAAP

    @Column(nullable = false, length = 5)
    private String currency;

    /**
     * Presentation currency this period is normalised into for cross-period
     * comparison (trends) and cross-facility analysis. Defaults to the latest
     * period's currency. Level-1 (financial-analysis) currency.
     */
    @Column(length = 5)
    private String presentationCurrency;

    /**
     * Multiplier from this period's native {@link #currency} to the
     * {@link #presentationCurrency}: presentationValue = nativeValue * fxToPresentation.
     * 1.0 when native == presentation. Sourced from the same limit-service
     * FxService that drives the system-currency (Level-2) conversion, or
     * supplied per-period by the analyst (period-end rate).
     */
    private Double fxToPresentation;

    /** ISO period-end date (e.g. 2024-03-31) — drives the dated FX_RATE lookup. */
    @Column(length = 12)
    private String periodEnd;

    /** Provenance of {@link #fxToPresentation}: SUPPLIED | DATED_MASTER | CURRENT_SPOT | SAME_CURRENCY. */
    @Column(length = 16)
    private String fxRateSource;

    @Column(nullable = false)
    private int ordinal;               // ordering: 0 = latest
}
