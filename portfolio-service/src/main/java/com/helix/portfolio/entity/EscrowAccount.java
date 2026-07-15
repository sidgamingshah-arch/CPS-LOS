package com.helix.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * An escrow monitoring account. A monitoring / record surface only: it captures the
 * opening balance, currency and the subject (an obligor or a facility) an escrow relates
 * to, then hosts append-only versioned budget lines and tagged transactions for a
 * deterministic budget-vs-actual read.
 *
 * <p>It NEVER mutates an authoritative figure (ECL / IRAC / exposure / limit). Those live
 * on {@link EclResult} / {@link ExposureRecord} (and in limit-service) and are untouched
 * here. Column names are reserved-word-safe.</p>
 */
@Entity
@Table(name = "escrow_accounts", indexes = {
        @Index(name = "uq_escrow_ref", columnList = "escrowRef", unique = true),
        @Index(name = "idx_escrow_subject", columnList = "subjectRef"),
        @Index(name = "idx_escrow_status", columnList = "status")
})
@Getter
@Setter
public class EscrowAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code ESC-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String escrowRef;

    /** What the escrow is about — OBLIGOR / FACILITY (free text). */
    @Column(length = 60)
    private String subjectType;

    @Column(length = 120)
    private String subjectRef;

    /** Optional descriptive name / purpose of the escrow. */
    @Column(length = 240)
    private String purpose;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false)
    private double openingBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EscrowAccountStatus status = EscrowAccountStatus.ACTIVE;

    @Column(nullable = false, length = 120)
    private String createdBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
