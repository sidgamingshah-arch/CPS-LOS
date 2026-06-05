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
 * The structural variant of a credit proposal / deal (PRD specialised CP variants):
 * single obligor, group, joint-obligor, dual-obligor (Islamic), syndication, or an
 * FI internal-credit-review (ICR) facility. One per application; participants hang
 * off {@link DealParticipant}. A renewal/amendment proposal carries
 * {@code copiedFromReference} to record the source it was copied from.
 */
@Entity
@Table(name = "deal_structures", indexes = {
        @Index(name = "idx_struct_app", columnList = "applicationReference")
})
@Getter
@Setter
public class DealStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 20)
    private String structureType;     // SINGLE | GROUP | JOINT_OBLIGOR | DUAL_OBLIGOR | SYNDICATION | FI_ICR

    @Column(nullable = false)
    private boolean islamic;

    private String groupReference;    // for GROUP structures
    private String leadArranger;      // for SYNDICATION

    private double totalDealAmount;   // total syndicated/club amount
    private double ourShareAmount;    // our bank's committed share
    private double ourSharePct;

    private String copiedFromReference;  // renewal/amendment source proposal
    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
