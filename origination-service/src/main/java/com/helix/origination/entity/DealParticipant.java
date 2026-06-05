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
 * A party to a structured deal (PRD multi-party / joint-obligor / syndication).
 * Roles cover obligors (primary/co), guarantors, group members, and — for
 * syndication — the lead bank and participant lenders with their committed shares.
 */
@Entity
@Table(name = "deal_participants", indexes = {
        @Index(name = "idx_part_app", columnList = "applicationReference")
})
@Getter
@Setter
public class DealParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long applicationId;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 24)
    private String role;              // PRIMARY_OBLIGOR | CO_OBLIGOR | GUARANTOR | GROUP_MEMBER
                                      // | LEAD_BANK | PARTICIPANT_LENDER

    @Column(nullable = false)
    private String name;

    private String externalRef;       // CIF / counterparty reference
    private double sharePct;          // obligor liability share or lender participation %
    private double obligationAmount;  // obligor's share of the obligation
    private double committedAmount;    // lender's committed amount (syndication)

    @Column(length = 20)
    private String liabilityType;     // JOINT | SEVERAL | JOINT_AND_SEVERAL

    @Column(nullable = false)
    private int ordinal;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
