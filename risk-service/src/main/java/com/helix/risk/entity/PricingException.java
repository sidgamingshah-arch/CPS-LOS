package com.helix.risk.entity;

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
 * A pricing-exception (concession) approval (PRD pricing-approval sub-workflow).
 * When a relationship manager proposes a rate below the model-recommended rate, the
 * concession is routed to an authority tier sized to the concession magnitude and
 * whether it breaks the RAROC hurdle, then approved through a maker-checker flow
 * (one or two levels) with segregation of duties. The RAROC is re-computed at the
 * proposed rate so the approver sees the economic impact. Advisory to the figure
 * path — the authoritative {@link PricingResult} is never overwritten.
 */
@Entity
@Table(name = "pricing_exceptions", indexes = {
        @Index(name = "idx_pex_app", columnList = "applicationReference"),
        @Index(name = "idx_pex_status", columnList = "status")
})
@Getter
@Setter
public class PricingException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String applicationReference;

    @Column(nullable = false)
    private double recommendedRate;
    @Column(nullable = false)
    private double proposedRate;
    @Column(nullable = false)
    private double concessionBps;        // (recommended - proposed) * 10000

    @Column(nullable = false)
    private double proposedRaroc;        // RAROC recomputed at the proposed rate
    @Column(nullable = false)
    private double hurdleRaroc;
    @Column(nullable = false)
    private boolean belowHurdle;

    @Column(nullable = false)
    private double ead;

    @Column(nullable = false, length = 30)
    private String requiredAuthority;    // RELATIONSHIP_HEAD | CREDIT_OFFICER | CREDIT_HEAD | CREDIT_COMMITTEE | NONE

    @Column(nullable = false)
    private int requiredLevels;          // 0 (auto), 1, or 2

    @Column(nullable = false, length = 16)
    private String status;               // PENDING_L1 | PENDING_L2 | APPROVED | REJECTED

    private String reason;
    private String proposedBy;
    private String approverL1;
    private String approverL2;
    private String decisionComment;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> breakdown;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    private Instant decidedAt;
}
