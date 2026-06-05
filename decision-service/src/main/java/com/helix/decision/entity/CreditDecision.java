package com.helix.decision.entity;

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
 * A credit decision routed via the delegated-authority matrix (PRD §8). The
 * outcome is always a named human action — AI never approves. Conditions become
 * tracked CP/CS items; deviations and the routing rule applied are recorded.
 */
@Entity
@Table(name = "credit_decisions", indexes = {
        @Index(name = "idx_decision_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CreditDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false)
    private String counterpartyName;

    // ---- routing inputs (system-of-record, fetched from upstream) ----
    private double amount;
    private String currency;
    private String segment;
    private String finalGrade;
    private double raroc;
    private boolean belowHurdle;
    private boolean ratingEscalated;

    @Column(nullable = false, length = 30)
    private String requiredAuthority;       // RM_HEAD | CREDIT_OFFICER | CREDIT_COMMITTEE | BOARD_COMMITTEE

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 1000)
    private List<String> deviations;

    @Column(nullable = false, length = 30)
    private String status;                  // PENDING_APPROVAL | DECIDED

    // ---- the decision itself ----
    private String outcome;                 // Enums.DecisionOutcome name
    private String decidedBy;
    private String deciderRole;
    private Instant decidedAt;

    @Lob
    @Column(length = 4000)
    private String rationale;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 4000)
    private List<String> conditions;

    @Lob
    @Column(length = 2000)
    private String dissent;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
