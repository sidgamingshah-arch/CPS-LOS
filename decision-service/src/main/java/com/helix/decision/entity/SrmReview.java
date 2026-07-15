package com.helix.decision.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.Map;

/**
 * A <b>Structured Review / renewal (SRM)</b> — the periodic renewal decision for an
 * obligor or facility. It is deliberately <i>thin</i>: it does not re-implement any
 * approval lifecycle. Instead it materialises a renewal checklist (from the
 * {@code SRM_CHECKLIST} master, config-as-data) and delegates the actual governed
 * decision to the existing Noting engine — a {@code NOTING_TYPE=SRM_RENEWAL} noting,
 * whose ref is linked here. When that noting reaches {@code AUTHORIZED}, the SRM
 * observes it and advances the subject's MER next-review/renewal due date.
 *
 * <p>Like a noting, an SRM review is a RECORD: it never mutates a limit, exposure,
 * rating or price.
 */
@Entity
@Table(name = "srm_reviews", indexes = {
        @Index(name = "idx_srm_ref", columnList = "srmRef", unique = true),
        @Index(name = "idx_srm_subject", columnList = "subjectRef"),
        @Index(name = "idx_srm_status", columnList = "status")
})
@Getter
@Setter
public class SrmReview {

    /** SRM review lifecycle (distinct from the linked noting's own status machine). */
    public enum Status {
        OPEN, NOTING_RAISED, COMPLETED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String srmRef;                 // SRM-XXXXXX

    @Column(length = 30)
    private String subjectType;            // Counterparty | Facility | Application

    @Column(nullable = false, length = 60)
    private String subjectRef;             // obligor / facility ref (also the MER reference)

    private String counterpartyName;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 60)
    private String checklistKey;           // SRM_CHECKLIST master recordKey used

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> checklist; // { items: [ {code, label, mandatory, done} ] }

    @Column(length = 20)
    private String notingRef;              // linked SRM_RENEWAL noting (NTG-XXXXXX)

    @Column(length = 20)
    private String notingStatus;           // last observed status of the linked noting

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false, length = 60)
    private String raisedBy;

    private String renewalDueDate;         // ISO date the MER next-review was advanced to (display only)

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
