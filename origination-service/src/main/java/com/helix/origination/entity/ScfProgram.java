package com.helix.origination.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Map;

/**
 * A Supply-Chain Finance (SCF) <b>product paper / programme</b> — an anchor-backed
 * vendor- or dealer-finance limit that spokes (the anchor's suppliers or distributors)
 * draw against. The programme carries a total {@code programLimit}, a {@code perSpokeCap},
 * and a pinned snapshot of the {@code SCF_ELIGIBILITY} master criteria used to judge every
 * spoke deterministically. Lifecycle: DRAFT → PENDING_APPROVAL → APPROVED | REJECTED |
 * WITHDRAWN. On submit a linked {@code PRODUCT_PAPER} noting is raised in decision-service
 * (best-effort — a noting-service outage never blocks SCF). On approval the programme limit
 * is best-effort registered into limit-service's own governed limit tree. SCF never mutates
 * an authoritative figure directly.
 */
@Entity
@Table(name = "scf_programs", indexes = {
        @Index(name = "idx_scf_ref", columnList = "scfRef", unique = true),
        @Index(name = "idx_scf_anchor", columnList = "anchorRef"),
        @Index(name = "idx_scf_status", columnList = "status")
})
@Getter
@Setter
public class ScfProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20, unique = true)
    private String scfRef;                 // SCF-XXXXXX

    @Column(nullable = false, length = 40)
    private String anchorRef;              // anchor counterparty reference

    @Column(length = 200)
    private String anchorName;

    @Column(nullable = false, length = 20)
    private String programType;            // VENDOR | DEALER

    @Column(name = "program_limit", nullable = false)
    private double programLimit;

    @Column(name = "per_spoke_cap", nullable = false)
    private double perSpokeCap;

    @Column(nullable = false, length = 10)
    private String currency;               // e.g. INR

    /** Pinned SCF_ELIGIBILITY criteria captured at create time (config-as-data snapshot). */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(name = "eligibility_snapshot", length = 4000)
    private Map<String, Object> eligibilitySnapshot;

    @Column(nullable = false, length = 20)
    private String status;                 // DRAFT | PENDING_APPROVAL | APPROVED | REJECTED | WITHDRAWN

    @Column(length = 20)
    private String notingRef;              // linked PRODUCT_PAPER noting (nullable; best-effort)

    @Column(length = 40)
    private String registeredLimitRef;     // limit-service root node reference (nullable; best-effort)

    @Column(nullable = false, length = 60)
    private String raisedBy;

    private String decidedBy;
    private Instant decidedAt;

    @Column(length = 2000)
    private String decisionNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
