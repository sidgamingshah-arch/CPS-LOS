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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Map;

/**
 * A governed <b>Noting</b> — an Indian-bank decision RECORD (TOD/intraday excess note,
 * CAM note, product paper, deferral extension/waiver/closure, second-stage disbursement
 * note, SRM renewal note). It routes for approval and is authoritative <i>as a record</i>,
 * but it NEVER mutates an authoritative figure (limits, exposures, ratings, pricing, booked
 * amounts). The status machine is DRAFT → PENDING_APPROVAL → APPROVED | PENDING_CAD →
 * AUTHORIZED, with REJECTED / REVERSED / WITHDRAWN terminals.
 */
@Entity
@Table(name = "notings", indexes = {
        @Index(name = "idx_noting_ref", columnList = "notingRef", unique = true),
        @Index(name = "idx_noting_subject", columnList = "subjectRef"),
        @Index(name = "idx_noting_status", columnList = "status"),
        @Index(name = "idx_noting_type", columnList = "notingType")
})
@Getter
@Setter
public class Noting {

    /** Lifecycle of a noting record. Persisted as a String (never an ordinal). */
    public enum Status {
        DRAFT, PENDING_APPROVAL, APPROVED, PENDING_CAD, AUTHORIZED, REJECTED, REVERSED, WITHDRAWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String notingRef;              // NTG-XXXXXX

    @Column(nullable = false, length = 40)
    private String notingType;             // NOTING_TYPE master key (e.g. TOD_INTRADAY, CAM_NOTE)

    @Column(length = 30)
    private String subjectType;            // Application | Counterparty | Facility | Group ...

    @Column(nullable = false, length = 60)
    private String subjectRef;             // e.g. application ref / obligor / facility ref

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(length = 16000)
    private String narrative;

    @Column(nullable = false, length = 60)
    private String raisedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 20)
    private String routing;                // DOA | FIXED_ROLE (resolved at submit)

    @Column(length = 40)
    private String approverRole;           // routed authority/role (nullable until submit)

    @Column(length = 60)
    private String approver;               // actor who approved (nullable)

    @Column(nullable = false)
    private boolean cadRequired;

    private String decidedBy;
    private Instant decidedAt;

    @Column(length = 2000)
    private String decisionNote;

    private String authorisedBy;
    private Instant authorisedAt;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> payload;   // type-specific fields (amount, grade, jurisdiction, ...)

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
