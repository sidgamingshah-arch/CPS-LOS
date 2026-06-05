package com.helix.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * An early-warning signal (PRD §11, US-11.3). The EWS agent scans and ranks
 * autonomously [A], but only flags — it cannot reclassify or re-stage. Humans
 * decide classification and remediation.
 */
@Entity
@Table(name = "ews_signals", indexes = {
        @Index(name = "idx_ews_app", columnList = "applicationReference"),
        @Index(name = "idx_ews_status", columnList = "status")
})
@Getter
@Setter
public class EwsSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    private String counterpartyRef;
    private String counterpartyName;

    @Column(nullable = false, length = 60)
    private String signalType;         // COVENANT_BREACH, LEVERAGE_SPIKE, DPD, ADVERSE_MEDIA, ...

    @Column(nullable = false, length = 20)
    private String severity;           // Enums.SignalSeverity name

    @Column(nullable = false, length = 20)
    private String source;             // INTERNAL | EXTERNAL

    private double score;              // composite signal score 0..1

    @Lob
    @Column(nullable = false, length = 2000)
    private String rationale;

    private String proposedAction;     // proposed only — never executed by the agent

    @Column(nullable = false, length = 20)
    private String status = "OPEN";    // OPEN | REVIEWED | DISMISSED

    private String reviewedBy;
    private Instant reviewedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
