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
 * One tranche / drawdown of a sanctioned facility. Multiple {@code Disbursement}
 * rows per facility model PF milestone draws, partial WC draws, and revolver use.
 * The lifecycle is:
 *
 * <pre>
 *   DRAFT  → AUTHORIZED → RELEASED
 *     ↓         ↓
 *   REJECTED  REJECTED
 * </pre>
 *
 * <p>Authorization is gated by the pre-disbursement CP check: if any mandatory CP
 * on the facility is still OPEN, {@code authorize()} returns 403 with the blocker
 * list. Release calls limit-service {@code /utilise} and stamps the audit trail.</p>
 *
 * <p>SoD: the requester, authoriser, and releaser must all be distinct named
 * humans — matching the maker-checker pattern used elsewhere.</p>
 */
@Entity
@Table(name = "disbursements", indexes = {
        @Index(name = "idx_disb_app_facility", columnList = "applicationReference,facilityRef"),
        @Index(name = "idx_disb_status", columnList = "status")
})
@Getter
@Setter
public class Disbursement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String facilityRef;

    /** Sequential within (applicationReference, facilityRef) starting from 1. */
    @Column(nullable = false)
    private int drawdownNo;

    /** For PF tranches: the construction milestone this draw is against (else null). */
    private Integer milestoneSequence;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false, length = 8)
    private String currency;

    /**
     * For cross-currency drawdowns: the amount the requester actually asked for
     * (e.g. 1,000 USD on an INR-denominated facility). {@link #amount} is the
     * facility-currency normalised value used for headroom and limit booking;
     * {@code requestedAmount} + {@code requestedCurrency} are kept for audit /
     * statement printing. {@code fxRate} is the conversion that was applied
     * (requestedCcy → facilityCcy).
     */
    private Double requestedAmount;
    @Column(length = 8) private String requestedCurrency;
    private Double fxRate;

    /** Amount in base currency, captured at release-time for the audit row. */
    @Column(nullable = false)
    private double baseAmount;

    @Column(length = 200)
    private String purpose;

    @Column(length = 200)
    private String narrative;

    /** DRAFT · AUTHORIZED · RELEASED · REJECTED · CANCELLED · REVERSED */
    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(nullable = false, length = 80)
    private String requestedBy;

    @CreationTimestamp
    private Instant requestedAt;

    @Column(length = 80) private String authorizedBy;
    private Instant authorizedAt;

    @Column(length = 80) private String releasedBy;
    private Instant releasedAt;
    /** The limit-service utilisation transactionRef for this release (audit pointer). */
    @Column(length = 80) private String utilisationRef;

    @Column(length = 80)  private String rejectedBy;
    @Column(length = 400) private String rejectedReason;
    private Instant rejectedAt;

    /** Voluntary cancellation by the requester (distinct from a checker's REJECTED). */
    @Column(length = 80)  private String cancelledBy;
    @Column(length = 400) private String cancelledReason;
    private Instant cancelledAt;

    /** Post-release reversal — undoes the limit booking via a REVERSAL action. */
    @Column(length = 80)  private String reversedBy;
    @Column(length = 400) private String reversedReason;
    private Instant reversedAt;
    /** The limit-service REVERSAL transactionRef (audit pointer, REV-<utilisationRef>). */
    @Column(length = 90)  private String reversalRef;

    @UpdateTimestamp
    private Instant updatedAt;
}
