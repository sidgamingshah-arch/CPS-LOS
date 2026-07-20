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

import java.time.Instant;

/**
 * A credit-bureau report ingested for a counterparty via the canonical connector contract
 * (PRD §8). This is INPUT / provenance data only — it is flagged {@code advisory} and NEVER
 * creates or mutates a Rating, PD/LGD or any authoritative figure. Column names are chosen to
 * avoid SQLite reserved words.
 */
@Entity
@Table(name = "bureau_records", indexes = {
        @Index(name = "idx_bureau_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class BureauRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterpartyId;

    private String subjectName;
    private String subjectIdentifier;

    // Bureau score is stored purely as input/provenance — never an authoritative figure.
    private Integer creditScore;

    @Column(length = 40)
    private String scoreModel;

    @Column(name = "inquiries_6m")
    private int inquiriesLast6m;

    @Column(name = "delinquencies_24m")
    private int delinquenciesLast24m;

    @Column(name = "open_tradelines")
    private int openTradelines;

    @Column(name = "total_outstanding")
    private double totalOutstanding;

    private Integer oldestAccountMonths;

    // ---- provenance (figure -> source -> version trace) ----
    @Column(nullable = false, length = 40)
    private String sourceSystem;      // CREDIT_BUREAU

    @Column(length = 80)
    private String sourceVendor;      // e.g. CIBIL / Experian / simulated-bureau

    @Column(length = 120)
    private String sourceReference;   // idempotency key

    @Column(length = 40)
    private String payloadVersion;

    private Instant retrievedAt;

    /** Ingested external data is advisory INPUT, never an authoritative figure. */
    @Column(nullable = false)
    private boolean advisory = true;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
