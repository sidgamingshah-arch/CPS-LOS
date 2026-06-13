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
 * A secondary-market transfer of UNFUNDED commitment from one syndicate lender
 * to another (existing participant or new entrant). Funded historical
 * allocations stay with the original lender — only future draws follow the new
 * commitment split. This matches market practice: novation of the undrawn
 * portion, with sold-down loan participations sitting separately on the
 * agent's secondary register.
 *
 * <p>Lifecycle: PROPOSED → SETTLED (agent-approved, participants re-cut) /
 * REJECTED. SoD: the agent (lead-bank actor) must differ from the transferor.</p>
 */
@Entity
@Table(name = "secondary_transfers", indexes = {
        @Index(name = "idx_sxfer_app", columnList = "applicationReference"),
        @Index(name = "idx_sxfer_status", columnList = "status")
})
@Getter
@Setter
public class SecondaryTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    /** The selling lender (existing DealParticipant). */
    @Column(nullable = false)
    private Long fromParticipantId;

    @Column(nullable = false, length = 120)
    private String fromName;

    @Column(nullable = false, length = 120)
    private String toBank;

    @Column(length = 60)
    private String toBankRef;

    @Column(nullable = false)
    private double transferAmount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(length = 400)
    private String reason;

    /** PROPOSED · SETTLED · REJECTED */
    @Column(nullable = false, length = 20)
    private String status = "PROPOSED";

    @Column(nullable = false, length = 80)
    private String proposedBy;

    @CreationTimestamp
    private Instant proposedAt;

    @Column(length = 80) private String agentDecidedBy;
    private Instant agentDecidedAt;
    @Column(length = 400) private String decisionComment;

    /** The transferee DealParticipant on SETTLED — new row, or merged into an existing one. */
    private Long toParticipantId;

    @UpdateTimestamp
    private Instant updatedAt;
}
