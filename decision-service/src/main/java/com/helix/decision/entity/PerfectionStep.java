package com.helix.decision.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One ordered step within a {@link PerfectionCase}. Role-gated: an actor may only
 * complete / waive a step whose {@code ownerRole} they act as. The MOE-vetting step
 * additionally enforces segregation of duties — the vetting actor must differ from
 * the MOE-execution actor.
 *
 * <p>Column names are reserved-word-safe ({@code step_order}, not the SQLite keyword
 * {@code order}).</p>
 */
@Entity
@Table(name = "perfection_steps", indexes = {
        @Index(name = "idx_perfstep_case", columnList = "caseRef"),
        @Index(name = "idx_perfstep_key", columnList = "caseRef,stepKey")
})
@Getter
@Setter
public class PerfectionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String caseRef;           // parent PerfectionCase.perfRef

    @Column(nullable = false, length = 40)
    private String stepKey;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 20)
    private String ownerRole;         // LMO | CAD_OPS | VENDOR | LEGAL

    @Column(nullable = false, length = 20)
    private String status;            // PENDING | DONE | WAIVED

    @Lob
    @Column(length = 2000)
    private String evidence;          // DMS ref / evidence pointer

    @Lob
    @Column(length = 2000)
    private String notes;

    /** Set when this is a VENDOR step whose report was requested via the Query module. */
    @Column(length = 40)
    private String vendorQueryRef;

    /** Ordinal position in the checklist. "order" is a SQLite reserved word — mapped to step_order. */
    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    /** Named actor that moved the step to DONE / WAIVED — the SoD identity for MOE vetting. */
    @Column(length = 120)
    private String completedBy;

    private Instant completedAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
