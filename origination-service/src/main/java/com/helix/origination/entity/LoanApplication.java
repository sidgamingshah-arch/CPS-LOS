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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A wholesale credit application — the spine of the origination lifecycle
 * (PRD §5). Status advances through the staged pipeline; the spread must be
 * analyst-confirmed before it may feed rating/capital/pricing (PRD §4 HITL gate).
 */
@Entity
@Table(name = "loan_applications", indexes = {
        @Index(name = "idx_app_reference", columnList = "reference", unique = true),
        @Index(name = "idx_app_counterparty", columnList = "counterpartyRef")
})
@Getter
@Setter
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Column(nullable = false)
    private Long counterpartyId;

    @Column(nullable = false, length = 30)
    private String counterpartyRef;

    @Column(nullable = false)
    private String counterpartyName;

    @Column(nullable = false, length = 20)
    private String jurisdiction;

    @Column(nullable = false, length = 40)
    private String segment;

    /** Borrower sector (from the counterparty), resolved at create — drives sector-specific
     *  financial / projection / scoring-model templates. Nullable: legacy rows / unavailable. */
    @Column(length = 60)
    private String sector;

    @Column(nullable = false, length = 40)
    private String facilityType;

    @Column(nullable = false)
    private double requestedAmount;

    @Column(nullable = false, length = 5)
    private String currency;

    @Column(nullable = false)
    private int tenorMonths;

    private String purpose;

    // ---- collateral / security (feeds CRM in capital + LTV in structuring) ----
    private String collateralType;     // CASH, PROPERTY, RECEIVABLES, ...
    private double collateralValue;
    private boolean secured;

    @Column(nullable = false, length = 30)
    private String status;             // Enums.ApplicationStatus name

    /** True once the analyst confirms the spread; reset by any material override. */
    private boolean spreadConfirmed;

    /** Set when this application was materialised by converting an approved In-Principle
     *  note (IPN-…). Nullable — most applications are raised directly. */
    @Column(length = 20)
    private String ipNoteRef;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
