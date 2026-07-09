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

import java.time.Instant;

/**
 * Agency reconciliation record: one row per (drawdown × participant). When the
 * agent allocates a syndicated drawdown pro-rata to the lenders, each lender's
 * slice is persisted here. Idempotent on {@code drawdownRef} — re-allocating the
 * same drawdown returns the existing rows rather than double-counting.
 */
@Entity
@Table(name = "syndication_allocations", indexes = {
        @Index(name = "idx_synalloc_app", columnList = "applicationReference"),
        @Index(name = "idx_synalloc_draw", columnList = "applicationReference,drawdownRef")
})
@Getter
@Setter
public class SyndicationAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    /** A stable reference for the drawdown being allocated (e.g. the disbursement txn ref). */
    @Column(nullable = false, length = 80)
    private String drawdownRef;

    @Column(nullable = false)
    private Long participantId;

    @Column(nullable = false)
    private String participantName;

    @Column(nullable = false, length = 24)
    private String role;

    @Column(nullable = false)
    private double sharePct;

    @Column(nullable = false)
    private double drawdownAmount;

    @Column(nullable = false)
    private double allocatedAmount;

    @Column(nullable = false, length = 8)
    private String currency;

    /** ACTIVE | REVERSED — reversed rows stay for the audit trail but drop out of funded-to-date. */
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(length = 80) private String reversedBy;
    private Instant reversedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
