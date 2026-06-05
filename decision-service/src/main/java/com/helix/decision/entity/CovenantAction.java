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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A request-and-approve workflow action against a covenant schedule (PRD Covenant
 * Tracking Workflow). Maker-checker with segregation of duties — the approver
 * cannot be the requester. Approval of an extension advances the due date; approval
 * of a waiver puts the schedule into WAIVED state.
 */
@Entity
@Table(name = "covenant_actions", indexes = {
        @Index(name = "idx_action_sched", columnList = "scheduleId")
})
@Getter
@Setter
public class CovenantAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleId;

    @Column(nullable = false, length = 30)
    private String action;          // REQUEST_EXTENSION | REQUEST_WAIVER | FREEZE_ACCOUNTS | FREEZE_DISBURSEMENT

    @Column(nullable = false, length = 20)
    private String status;          // PENDING | APPROVED | REJECTED

    private LocalDate newDueDate;   // for extensions
    private String reason;

    private String requestedBy;
    private String decidedBy;
    private String decisionComment;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant requestedAt;

    private Instant decidedAt;
}
