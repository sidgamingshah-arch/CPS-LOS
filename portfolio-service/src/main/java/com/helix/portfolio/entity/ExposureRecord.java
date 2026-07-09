package com.helix.portfolio.entity;

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

/** A booked exposure for portfolio analytics (PRD §7 Exposure / §12). */
@Entity
@Table(name = "exposure_records", indexes = {
        @Index(name = "idx_exposure_app", columnList = "applicationReference", unique = true),
        @Index(name = "idx_exposure_cp", columnList = "counterpartyRef"),
        @Index(name = "idx_exposure_sector", columnList = "sector")
})
@Getter
@Setter
public class ExposureRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 30)
    private String counterpartyRef;

    @Column(nullable = false)
    private String counterpartyName;

    private String jurisdiction;
    private String segment;
    private String sector;

    /** Extra concentration dimensions (instrument / duration / counterparty-group). */
    private String facilityType;     // instrument dimension
    private Integer tenorMonths;     // drives the duration-bucket dimension
    private String groupRef;         // counterparty-group dimension (falls back to the obligor ref)

    @Column(nullable = false, length = 5)
    private String finalGrade;

    /**
     * Grade at the FIRST booking of this exposure — immutable once set (never overwritten on
     * re-register). Baseline for the SICR-by-notch rule + rating-transition tracking. Nullable
     * for legacy rows; a null origination disables the notch rule (no-op, current behaviour).
     */
    @Column(length = 5)
    private String originationGrade;

    /**
     * Collateral snapshot at booking — drives the RBI doubtful-asset secured/unsecured
     * provisioning split. Nullable for legacy rows; a null/absent value is treated as
     * unsecured (the conservative 100%-unsecured path).
     */
    private Double collateralValue;
    private Boolean secured;

    private double pd;
    private double lgd;
    private double ead;
    private double rwa;
    private double capitalRequired;
    private String currency;

    /** Days past due — drives SICR staging and IRAC classification. */
    private int daysPastDue;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
