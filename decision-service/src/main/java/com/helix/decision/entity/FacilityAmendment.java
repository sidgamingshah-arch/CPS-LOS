package com.helix.decision.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A post-sanction facility amendment — increase / decrease / tenor extension —
 * routed through the same DoA matrix that sanctioned the deal. The required
 * authority is computed from the POST-amendment total application exposure and
 * the current rating grade; the approver must hold a role of at least that
 * authority rank (RBAC) and differ from the proposer (SoD).
 *
 * <p>Lifecycle: PROPOSED → APPROVED (applied to origination + limit tree) or
 * REJECTED. One open amendment per facility at a time.</p>
 */
@Entity
@Table(name = "facility_amendments", indexes = {
        @Index(name = "idx_amend_app_facility", columnList = "applicationReference,facilityRef"),
        @Index(name = "idx_amend_status", columnList = "status")
})
@Getter
@Setter
public class FacilityAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String facilityRef;

    /** INCREASE · DECREASE · TENOR_EXTENSION · MIXED */
    @Column(nullable = false, length = 20)
    private String amendmentType;

    @Column(nullable = false) private double currentAmount;
    private Double proposedAmount;
    @Column(nullable = false) private int currentTenorMonths;
    private Integer proposedTenorMonths;

    /** Post-amendment total application exposure the DoA routing was computed on. */
    @Column(nullable = false) private double routedExposure;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(length = 400)
    private String reason;

    /** DoA authority required to approve (RM_HEAD / CREDIT_OFFICER / CREDIT_COMMITTEE / BOARD_COMMITTEE). */
    @Column(nullable = false, length = 30)
    private String requiredAuthority;

    @Column(length = 200)
    private String ruleApplied;

    /** PROPOSED · APPROVED · REJECTED */
    @Column(nullable = false, length = 20)
    private String status = "PROPOSED";

    @Column(nullable = false, length = 80)
    private String proposedBy;

    @CreationTimestamp
    private Instant proposedAt;

    @Column(length = 80) private String decidedBy;
    private Instant decidedAt;
    @Column(length = 400) private String decisionComment;

    /** Stamped once origination + limit-service have both applied the change. */
    private Instant appliedAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
