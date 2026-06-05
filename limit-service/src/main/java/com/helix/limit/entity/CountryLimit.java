package com.helix.limit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Country-level exposure ledger (PRD Country Level Risk Assessment Framework /
 * country limits). Departments sit beneath the country and are non-fungible
 * between each other but roll up to the country cap.
 */
@Entity
@Table(name = "country_limits", indexes = {
        @Index(name = "idx_country", columnList = "country", unique = true)
})
@Getter
@Setter
public class CountryLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 5)
    private String country;

    @Column(nullable = false)
    private double overallLimit;
    @Column(nullable = false, length = 5)
    private String currency;
    private double outstanding;

    private LocalDate nextReviewDate;
    private LocalDate validityDate;

    @Column(length = 30)
    private String externalRating;       // lowest of agencies maintained externally

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";    // ACTIVE | FROZEN | UNDER_REVIEW

    @UpdateTimestamp
    private Instant updatedAt;
}
