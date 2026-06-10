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
 * A condition precedent on a specific facility within an application. CPs are the
 * explicit list of things that must be {@code CLEARED} (or {@code WAIVED} with an
 * approver) before the <i>first</i> drawdown of that facility can be authorised.
 *
 * <p>Separate from the CAD checklist: CAD captures the post-sanction-pre-disbursement
 * documentation collection, where each item is evidence-gathering — but multiple CAD
 * items may roll up to a single CP (e.g. four documents collectively prove
 * "security perfection"), and CPs may exist that have no CAD evidence at all
 * (e.g. "no material adverse change since sanction"). The pre-disbursement gate
 * checks <b>this</b> register, not CAD directly.</p>
 *
 * <p>The list is seeded at sanction-time from {@code CP_MASTER} (keyed by facility
 * type + jurisdiction with fallback), then maintained manually — credit ops or
 * the RM can add custom items and clear them as evidence arrives.</p>
 */
@Entity
@Table(name = "condition_precedents", indexes = {
        @Index(name = "idx_cp_app_facility", columnList = "applicationReference,facilityRef"),
        @Index(name = "idx_cp_status", columnList = "status")
})
@Getter
@Setter
public class ConditionPrecedent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    /** The facility this CP applies to (the proposed-facility {@code ref}). */
    @Column(nullable = false, length = 60)
    private String facilityRef;

    /** Short stable code from the master, or {@code CUSTOM-N} for custom items. */
    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 600)
    private String description;

    /** Mandatory CPs block drawdown authorisation. Non-mandatory show as advisory. */
    @Column(nullable = false)
    private boolean mandatory = true;

    /** OPEN · CLEARED · WAIVED · REJECTED */
    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    /** TEMPLATE (from CP_MASTER) or CUSTOM (added on the deal). */
    @Column(nullable = false, length = 20)
    private String source = "TEMPLATE";

    /** Pointer to the CAD checklist item id, document id, covenant, etc. */
    @Column(length = 200)
    private String evidenceRef;

    @Column(length = 80) private String clearedBy;
    private Instant clearedAt;

    @Column(length = 80) private String waivedBy;
    @Column(length = 400) private String waivedReason;
    private Instant waivedAt;

    @Column(length = 80) private String rejectedBy;
    @Column(length = 400) private String rejectedReason;
    private Instant rejectedAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
