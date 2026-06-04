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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Corrective Action Plan (PRD CAP module). Any remedial action a credit team
 * needs from a business user against a high/medium/low risk borrower — defined
 * action, target date, reminders, escalation, evidence on completion, then a
 * final review by the credit team.
 *
 * State machine: OPEN → IN_PROGRESS (response submitted) → COMPLETED (credit
 * approves) or OVERDUE / ESCALATED.
 */
@Entity
@Table(name = "corrective_actions", indexes = {
        @Index(name = "idx_cap_app", columnList = "applicationReference"),
        @Index(name = "idx_cap_owner", columnList = "owner"),
        @Index(name = "idx_cap_status", columnList = "status")
})
@Getter
@Setter
public class CorrectiveAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    /** EWS signal that triggered this CAP, if any. */
    private Long signalId;

    @Column(nullable = false)
    @Lob
    private String description;

    @Column(nullable = false, length = 20)
    private String criticality;   // HIGH | MEDIUM | LOW

    @Column(nullable = false)
    private LocalDate targetDate;

    @Column(nullable = false)
    private String owner;          // business user / RM assigned to deliver

    @Column(nullable = false)
    private String raisedBy;       // credit officer who created the CAP

    @Column(nullable = false, length = 20)
    private String status = "OPEN"; // OPEN | IN_PROGRESS | COMPLETED | OVERDUE | ESCALATED

    private int reminderDaysBefore = 3;
    private int escalationDaysAfter = 5;

    // ---- response (business user) ----
    @Lob
    private String response;
    private String responseDocRef;     // DMS reference
    private String respondedBy;
    private Instant respondedAt;

    // ---- review / closure (credit team) ----
    private String closedBy;
    private Instant closedAt;
    private String closureComment;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
