package com.helix.counterparty.entity;

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
 * A verified, risk-rated counterparty identity (PRD §7 Counterparty object).
 * KYC state and CDD tier are first-class; group/UBO linkage lives in the UBO graph.
 */
@Entity
@Table(name = "counterparties", indexes = {
        @Index(name = "idx_cp_reference", columnList = "reference", unique = true)
})
@Getter
@Setter
public class Counterparty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String reference;

    @Column(nullable = false)
    private String legalName;

    @Column(length = 60)
    private String legalForm;          // PRIVATE_LTD, PUBLIC_LTD, LLP, PARTNERSHIP, ...

    private String registrationNo;     // CIN / trade licence number

    @Column(length = 20)
    private String jurisdiction;       // IN-RBI | AE-CBUAE — drives CDD rule pack

    @Column(length = 60)
    private String segment;            // Enums.Segment name

    private String sector;
    private String country;

    /**
     * Borrower-level Level-1 presentation currency — the currency the borrower's
     * financials are analysed/normalised into across periods. Defaults from the
     * country/jurisdiction when not supplied; surfaced to spreading so a
     * multi-currency borrower has one consistent analysis currency.
     */
    @Column(length = 5)
    private String presentationCurrency;

    @Column(length = 20)
    private String cddTier;            // SIMPLIFIED | STANDARD | ENHANCED

    @Column(length = 20)
    private String kycStatus;          // Enums.KycStatus name

    // ---- risk flags feeding CDD intensity & screening (PRD §1) ----
    private boolean listedEntity;
    private boolean regulatedFi;
    private boolean pep;
    private boolean adverseMedia;
    private boolean highRiskJurisdiction;
    private boolean complexOwnership;

    private LocalDate reKycDueDate;

    private String verifiedBy;
    private Instant verifiedAt;

    // ---- credit-initiation lifecycle (prospect -> obligor) ----
    @Column(length = 20)
    private String recordType = "OBLIGOR";   // PROSPECT | OBLIGOR (existing flow defaults to OBLIGOR)

    @Column(length = 20)
    private String lifecycleStatus = "ACTIVE"; // DRAFT | ACTIVE | DROPPED | DISCARDED | CLOSED

    @Column(length = 20)
    private String borrowerType;             // NTB | ETB | DUAL_OBLIGOR

    private String rmId;                      // default RM = creator; reassignable via workflow
    private Long groupId;                     // tagged group, if any

    private String industry;
    private String subIndustry;
    private String businessSegment;
    private String subSegment;

    private String externalId;                // CRM / core-banking obligor id mapping
    private String droppedReason;
    private Instant lastActivityAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
