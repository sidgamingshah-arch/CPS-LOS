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
import java.time.LocalDate;

/**
 * A repayment against a facility — the inbound leg of the money movement that
 * {@link Disbursement} models outbound. Two entry channels:
 *
 * <ul>
 *   <li><b>MANUAL</b> — ops records it (RECORDED), a different actor confirms;
 *       confirmation books a {@code RELEASE} on the facility's limit node for the
 *       principal component, so the limit ledger stays the single source of truth
 *       for exposure.</li>
 *   <li><b>CORE_BANKING</b> — the servicing-system connector ingests a value-dated
 *       repayment event (idempotent on the envelope key) and books the RELEASE
 *       directly as a SYSTEM action — machine feeds are the truth, no maker-checker.</li>
 * </ul>
 *
 * <p>Lifecycle: RECORDED → CONFIRMED (or REJECTED by a checker). Only the
 * principal component moves the limit ledger; interest is income, not exposure.</p>
 */
@Entity
@Table(name = "repayments", indexes = {
        @Index(name = "idx_rpmt_app_facility", columnList = "applicationReference,facilityRef"),
        @Index(name = "idx_rpmt_status", columnList = "status")
})
@Getter
@Setter
public class Repayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String facilityRef;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false)
    private double principalComponent;

    @Column(nullable = false)
    private double interestComponent;

    @Column(nullable = false, length = 8)
    private String currency;

    private LocalDate valueDate;

    /** MANUAL | CORE_BANKING */
    @Column(nullable = false, length = 20)
    private String source = "MANUAL";

    /** External servicing-system reference (connector channel). */
    @Column(length = 80)
    private String externalRef;

    @Column(length = 200)
    private String narrative;

    /** RECORDED · CONFIRMED · REJECTED */
    @Column(nullable = false, length = 20)
    private String status = "RECORDED";

    @Column(nullable = false, length = 80)
    private String recordedBy;

    @CreationTimestamp
    private Instant recordedAt;

    @Column(length = 80) private String confirmedBy;
    private Instant confirmedAt;
    /** The limit-service RELEASE transactionRef booked on confirmation (audit pointer). */
    @Column(length = 90) private String releaseRef;

    @Column(length = 80)  private String rejectedBy;
    @Column(length = 400) private String rejectedReason;
    private Instant rejectedAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
