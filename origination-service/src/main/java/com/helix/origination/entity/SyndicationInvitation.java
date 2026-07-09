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
 * A lead bank's invitation to a prospective participant lender for a syndicated
 * facility. The acceptance flips into a {@link DealParticipant} row with role
 * PARTICIPANT_LENDER and the agreed commitment — until then the invitee is not
 * part of the syndicate book and gets no allocation.
 *
 * <p>Lifecycle: SENT → ACCEPTED (joins the syndicate) / DECLINED (with reason) /
 * EXPIRED (no decision by the cut-off) / WITHDRAWN (lead pulls the invite).
 * Each transition is SoD-gated: the invitee actor must differ from the lead
 * actor who sent the invitation.</p>
 */
@Entity
@Table(name = "syndication_invitations", indexes = {
        @Index(name = "idx_synvite_app", columnList = "applicationReference"),
        @Index(name = "idx_synvite_status", columnList = "status")
})
@Getter
@Setter
public class SyndicationInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 120)
    private String invitedBank;

    /** External reference for the invited bank (CIF / IFSC / SWIFT-style). */
    @Column(length = 60)
    private String invitedBankRef;

    @Column(nullable = false)
    private double proposedCommitment;

    @Column(nullable = false, length = 8)
    private String currency;

    /** Defaults to PARTICIPANT_LENDER; can be LEAD_BANK on a co-lead invite. */
    @Column(nullable = false, length = 24)
    private String proposedRole = "PARTICIPANT_LENDER";

    @Column(length = 400)
    private String terms;

    /** SENT · ACCEPTED · DECLINED · EXPIRED · WITHDRAWN */
    @Column(nullable = false, length = 20)
    private String status = "SENT";

    /** Lead-bank actor who sent the invitation. */
    @Column(nullable = false, length = 80)
    private String invitedBy;

    @CreationTimestamp
    private Instant invitedAt;

    /** Cut-off after which the invitation expires (rolling deadline). */
    private Instant expiresAt;

    @Column(length = 80) private String decidedBy;
    private Instant decidedAt;
    @Column(length = 400) private String declineReason;

    /** The DealParticipant created on ACCEPTED, for back-reference. */
    private Long participantId;

    @UpdateTimestamp
    private Instant updatedAt;
}
