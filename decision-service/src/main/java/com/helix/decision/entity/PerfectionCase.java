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

/**
 * A Mortgage / MOE (Memorandum-of-Entry) security-perfection case (Wave-2).
 * Opened over an obligor / facility / collateral to run the ordered perfection
 * checklist (title search → legal opinion → valuation → MOE execution → MOE
 * vetting → CERSAI filing). Steps are materialised from the {@code CHECKLIST_MASTER}
 * row keyed {@code PERFECTION_MOE} (with a conservative built-in fallback), and the
 * master version is pinned onto the case at creation time for traceability.
 */
@Entity
@Table(name = "perfection_cases", indexes = {
        @Index(name = "uq_perf_ref", columnList = "perfRef", unique = true),
        @Index(name = "idx_perf_subject", columnList = "subjectType,subjectRef"),
        @Index(name = "idx_perf_app", columnList = "applicationRef"),
        @Index(name = "idx_perf_status", columnList = "status")
})
@Getter
@Setter
public class PerfectionCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable reference, generated {@code PRF-XXXXXX}. */
    @Column(nullable = false, length = 40)
    private String perfRef;

    @Column(nullable = false, length = 20)
    private String subjectType;       // OBLIGOR | FACILITY | COLLATERAL

    @Column(nullable = false, length = 120)
    private String subjectRef;

    /** Optional deal linkage — drives the OPTIONAL, DEFAULT-OFF limit-release gate. */
    @Column(length = 30)
    private String applicationRef;

    @Column(nullable = false, length = 20)
    private String status;            // OPEN | IN_PROGRESS | COMPLETED | CANCELLED

    @Column(length = 60)
    private String checklistKey;      // CHECKLIST_MASTER recordKey used (PERFECTION_MOE)

    /** Pinned master version at creation time (null when the built-in fallback was used). */
    private Integer masterVersion;

    private String createdBy;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
