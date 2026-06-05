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

/** RM ownership assignment / claim, subject to the receiving RM's acceptance (PRD §1). */
@Entity
@Table(name = "ownership_assignments", indexes = {
        @Index(name = "idx_ownership_cp", columnList = "counterpartyId")
})
@Getter
@Setter
public class OwnershipAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long counterpartyId;

    private String fromRm;

    @Column(nullable = false)
    private String toRm;

    @Column(nullable = false, length = 20)
    private String mode;                // ASSIGN | CLAIM

    @Column(nullable = false, length = 20)
    private String status;              // PENDING | ACCEPTED | REJECTED

    private String requestedBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant requestedAt;

    private Instant decidedAt;
    private String note;
}
