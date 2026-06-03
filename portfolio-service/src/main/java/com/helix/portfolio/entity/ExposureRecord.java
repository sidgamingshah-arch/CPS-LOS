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

    @Column(nullable = false, length = 5)
    private String finalGrade;

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
