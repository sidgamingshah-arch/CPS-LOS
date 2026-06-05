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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A line in the Monitoring of Exceptions &amp; Renewals register (PRD MER / deferred-
 * documentation tracking). One per follow-up obligation: a document deferred at CAD,
 * a condition subsequent, or a recurring renewal (insurance, valuation, stock
 * statement, search report, financials, annual review).
 *
 * <p>State machine: OPEN -&gt; SUBMITTED -&gt; CLEARED, with OVERDUE / ESCALATED reached
 * by the sweep when the due date (plus escalation window) passes, and WAIVED reached
 * through a maker-checker waiver. Recurring items roll their due date forward on
 * clearance and re-open for the next cycle.
 *
 * <p>Clearance enforces segregation of duties (verifier != submitter); waivers enforce
 * verifier != owner. Submitting evidence emits a DMS feed event.
 */
@Entity
@Table(name = "mer_items", indexes = {
        @Index(name = "idx_mer_app", columnList = "applicationReference"),
        @Index(name = "idx_mer_owner", columnList = "owner"),
        @Index(name = "idx_mer_status", columnList = "status"),
        @Index(name = "idx_mer_due", columnList = "dueDate")
})
@Getter
@Setter
public class MerItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    private String counterpartyName;

    private Long cadCaseId;              // source CAD case, if generated from one
    private String sourceRef;            // checklist code / deviation id / collateral ref — dedup key

    @Column(nullable = false, length = 30)
    private String itemType;             // DEFERRED_DOCUMENT | CONDITION_SUBSEQUENT | INSURANCE
                                         // | VALUATION | STOCK_STATEMENT | SEARCH_REPORT | FINANCIALS | RENEWAL_REVIEW

    @Column(nullable = false, length = 20)
    private String category;             // DOCUMENT | CONDITION | RENEWAL

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, length = 10)
    private String criticality;          // HIGH | MEDIUM | LOW

    @Column(nullable = false)
    private LocalDate dueDate;           // clearance / expiry / review date

    @Column(nullable = false)
    private boolean recurring;

    @Column(length = 20)
    private String renewalFrequency;     // MONTHLY | QUARTERLY | HALF_YEARLY | ANNUAL (recurring only)

    @Column(nullable = false)
    private int reminderDaysBefore;

    @Column(nullable = false)
    private int escalationDaysAfter;

    @Column(nullable = false)
    private String owner;                // accountable RM / officer

    private String raisedBy;

    @Column(nullable = false, length = 20)
    private String status;               // OPEN | SUBMITTED | CLEARED | OVERDUE | ESCALATED | WAIVED

    private String docRef;               // DMS reference once evidence submitted
    private String submittedBy;
    private Instant submittedAt;
    private String clearedBy;            // verifier / waiver approver
    private Instant clearedAt;
    private String decisionComment;
    private LocalDate lastReminderAt;

    @Column(nullable = false)
    private int cycleCount;              // number of completed renewal cycles

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
