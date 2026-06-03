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

    @Column(nullable = false)
    private int ordinal;               // ordering: 0 = latest
}
