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
import java.time.LocalDate;

/**
 * Collateral / security as a first-class entity (PRD §7 Collateral). Multiple
 * items per application, each independently valued and perfected. The supervisory
 * haircut and perfection status feed CRM in the capital projection (PRD §6).
 */
@Entity
@Table(name = "collaterals", indexes = {
        @Index(name = "idx_collateral_app", columnList = "applicationId"),
        @Index(name = "idx_collateral_facility", columnList = "facilityId")
})
@Getter
@Setter
public class Collateral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    /** Facility-specific collateral if set; otherwise pools to the application. */
    private Long facilityId;

    @Column(nullable = false, length = 40)
    private String collateralType;     // CASH, PROPERTY, RECEIVABLES, EQUITY_LISTED, GOVT_SECURITIES, …

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private double marketValue;

    private LocalDate valuationDate;

    private String valuationSource;

    /** Supervisory haircut as a fraction (0..1); used by the capital projection. */
    @Column(nullable = false)
    private double haircut;

    /** Owner of the collateral (borrower / promoter / third party). */
    private String owner;

    private String location;

    /** PRD §9: charge registered / mortgage perfected, etc. Drives CRM eligibility. */
    @Column(nullable = false, length = 30)
    private String perfectionStatus;   // NOT_PERFECTED, IN_PROGRESS, PERFECTED

    private LocalDate perfectionDate;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    /** Effective coverage after the haircut. */
    public double effectiveValue() {
        return marketValue * (1 - haircut);
    }
}
