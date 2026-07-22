package com.helix.decision.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A signatory in the per-document signatory matrix (child of {@link DocumentExecution}).
 * Each row is one named party expected to sign on either the INTERNAL (bank) or CUSTOMER
 * side; {@code status} flips from PENDING to SIGNED when the sign action is recorded.
 */
@Entity
@Table(name = "signatories", indexes = {
        @Index(name = "idx_sig_doc", columnList = "documentId")
})
@Getter
@Setter
public class Signatory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Parent {@link DocumentExecution#getId()}. */
    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false, length = 120)
    private String signatoryName;

    @Column(length = 80)
    private String signatoryRole;

    @Column(name = "party_side", nullable = false, length = 20)
    private String side;              // INTERNAL | CUSTOMER

    @Column(nullable = false, length = 20)
    private String status;            // PENDING | SIGNED

    private Instant signedAt;
}
