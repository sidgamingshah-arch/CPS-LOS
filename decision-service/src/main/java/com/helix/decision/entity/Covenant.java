package com.helix.decision.entity;

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
import java.util.List;

/**
 * A structured, monitorable covenant rule object (PRD §7 sample covenant).
 * Designed at structuring; tested during monitoring (PRD §11).
 */
@Entity
@Table(name = "covenants", indexes = {
        @Index(name = "idx_covenant_app", columnList = "applicationReference")
})
@Getter
@Setter
public class Covenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String covenantType;       // FINANCIAL_MAINTENANCE | INFORMATION | NEGATIVE_PLEDGE

    @Column(nullable = false, length = 40)
    private String metric;             // DSCR, NET_LEVERAGE, INTEREST_COVERAGE, ...

    @Column(nullable = false, length = 5)
    private String operator;           // >=, <=, >, <, ==

    @Column(nullable = false)
    private double threshold;

    @Column(nullable = false, length = 20)
    private String testFrequency;      // MONTHLY | QUARTERLY | ANNUAL

    private String source;             // borrower_management_accounts, ...

    private int curePeriodDays;

    @Column(nullable = false, length = 20)
    private String breachSeverity;     // Enums.BreachSeverity name

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> onBreach;     // [notify_RM, raise_EWS, trigger_review]

    private boolean active = true;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
