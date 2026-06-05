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
 * A facility proposed under an application (PRD §7 Facility). An application may
 * propose multiple facilities (e.g. term loan + working-capital + LC line); each
 * is priced and tracked independently. The Application's inline facility fields
 * remain the "primary" facility for backward compatibility.
 */
@Entity
@Table(name = "proposed_facilities", indexes = {
        @Index(name = "idx_facility_app", columnList = "applicationId")
})
@Getter
@Setter
public class ProposedFacility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false, length = 30)
    private String reference;

    /** Order in the proposal: 0 = primary, then 1,2… for additional facilities. */
    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 40)
    private String facilityType;

    @Column(nullable = false)
    private double amount;

    @Column(nullable = false, length = 5)
    private String currency;

    @Column(nullable = false)
    private int tenorMonths;

    private String purpose;

    /** Indicative pricing carried at structuring; final pricing is the risk engine output. */
    private Double indicativeRate;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
