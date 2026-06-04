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
 * Monitoring schedule for a covenant (PRD Covenant Schedule Maintenance).
 * State machine: SCHEDULED -> {COMPLIANT, BREACHED, OVERDUE} -> {WAIVED, EXTENDED}.
 * Each due date triggers a test (CovenantTest) which updates {@code status} and
 * advances {@code currentDueDate} by the test frequency.
 */
@Entity
@Table(name = "covenant_schedules", indexes = {
        @Index(name = "idx_sched_app", columnList = "applicationReference"),
        @Index(name = "idx_sched_covenant", columnList = "covenantId"),
        @Index(name = "idx_sched_due", columnList = "currentDueDate")
})
@Getter
@Setter
public class CovenantSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long covenantId;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 40)
    private String metric;

    @Column(nullable = false, length = 20)
    private String testFrequency;       // MONTHLY | QUARTERLY | HALF_YEARLY | ANNUAL

    @Column(nullable = false)
    private LocalDate startDate;
    @Column(nullable = false)
    private LocalDate endDate;
    @Column(nullable = false)
    private LocalDate currentDueDate;

    @Column(nullable = false)
    private int graceDays;

    @Column(nullable = false, length = 20)
    private String status;              // SCHEDULED | COMPLIANT | BREACHED | OVERDUE | WAIVED | EXTENDED

    private LocalDate lastTestedAt;
    private LocalDate originalDueDate;  // captured when an extension is approved

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
