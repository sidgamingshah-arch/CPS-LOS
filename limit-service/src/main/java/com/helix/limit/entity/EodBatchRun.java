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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One end-of-day run of the limit batch (PRD limit EOD: currency revaluation +
 * utilisation reconciliation). Each run captures the FX rates used, the count of
 * nodes touched, the total revaluation delta (in INR) and the count of
 * reconciliation variances surfaced — all examiner-ready, immutable post-run.
 */
@Entity
@Table(name = "limit_eod_runs", indexes = {
        @Index(name = "idx_eod_date", columnList = "runDate")
})
@Getter
@Setter
public class EodBatchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private String runBy;

    @Column(nullable = false)
    private int totalNodes;
    @Column(nullable = false)
    private int revaluedCount;
    @Column(nullable = false)
    private int varianceCount;

    /** Net mark-to-market delta on sanctioned base, INR. */
    @Column(nullable = false)
    private double revaluationDeltaBase;

    @Column(length = 1000)
    private String fxSnapshot;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant completedAt;
}
