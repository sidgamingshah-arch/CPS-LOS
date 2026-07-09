package com.helix.decision.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.List;

/**
 * One committee member's vote on a credit decision routed to a COMMITTEE tier
 * (PRD §8 — committee/quorum decisioning). The decision finalises only when the
 * quorum of approving votes is reached; each vote is a named-human action with
 * segregation of duties (the deal's router cannot vote; no member votes twice).
 */
@Entity
@Table(name = "committee_votes", indexes = {
        @Index(name = "idx_vote_decision", columnList = "decisionId"),
        @Index(name = "idx_vote_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CommitteeVote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false)
    private Long decisionId;

    @Column(nullable = false, length = 80)
    private String voter;

    @Column(length = 40)
    private String voterRole;

    @Column(nullable = false, length = 30)
    private String voteOutcome;         // Enums.DecisionOutcome name

    @Lob
    @Column(length = 2000)
    private String rationale;

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 2000)
    private List<String> conditions;

    /** True for a non-approving vote (DECLINE / REFER) — recorded but not counted toward quorum. */
    private boolean dissent;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant castAt;
}
