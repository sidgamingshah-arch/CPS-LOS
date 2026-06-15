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
 * A collections case opened on a facility once its repayments fall overdue.
 *
 * <p><b>NPA staging</b> follows the standard 30/60/90 cuts:
 * STAGE_1 (≤30dpd) = performing-watch, STAGE_2 (≤90dpd) = SMA, STAGE_3 (>90dpd)
 * = NPA. The bucket recomputes on every {@code updateDpd} call so a cure flows
 * through automatically.</p>
 *
 * <p><b>Lifecycle</b>: OPEN → RESTRUCTURED / WRITTEN_OFF / LEGAL / CURED → CLOSED.
 * Restructure chains through the existing FacilityAmendment workflow (so the
 * same DoA matrix decides who can re-cut terms); write-off is its own
 * DoA-routed sub-workflow because the write-off amount needs an authority
 * signature regardless of the deal's original sanction.</p>
 */
@Entity
@Table(name = "collections_cases", indexes = {
        @Index(name = "idx_coll_app_facility", columnList = "applicationReference,facilityRef"),
        @Index(name = "idx_coll_status", columnList = "status"),
        @Index(name = "idx_coll_npa", columnList = "npaStage")
})
@Getter
@Setter
public class CollectionsCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String facilityRef;

    @Column(nullable = false, length = 80)
    private String counterpartyName;

    /** Days past due — drives NPA stage; refreshed via updateDpd or ingest. */
    @Column(nullable = false)
    private int daysPastDue;

    @Column(nullable = false)
    private double overdueAmount;

    @Column(nullable = false)
    private double outstandingAtOpen;

    @Column(nullable = false, length = 8)
    private String currency;

    /** STAGE_1 · STAGE_2 · STAGE_3 — IRAC-style staging, computed from DPD. */
    @Column(nullable = false, length = 12)
    private String npaStage;

    /** OPEN · RESTRUCTURED · WRITTEN_OFF · LEGAL · CURED · CLOSED */
    @Column(nullable = false, length = 20)
    private String status = "OPEN";

    @Column(length = 80) private String assignedTo;

    @Column(nullable = false, length = 80)
    private String openedBy;

    /** What surfaced the case — null for manual opens, else the monitoring trigger (e.g. EWS signal types). */
    @Column(length = 200)
    private String triggerReason;

    @CreationTimestamp
    private Instant openedAt;

    /** When a restructure amendment was approved this points at the FacilityAmendment row. */
    private Long restructureAmendmentId;

    /** Approved write-off amount in facility currency (null until WRITTEN_OFF). */
    private Double writeOffAmount;
    @Column(length = 80) private String writeOffProposedBy;
    @Column(length = 80) private String writeOffDecidedBy;
    @Column(length = 30) private String writeOffAuthority;

    /** Legal proceedings reference, if escalated. */
    @Column(length = 80) private String legalRef;

    @Column(length = 80) private String closedBy;
    private Instant closedAt;
    @Column(length = 400) private String closureNote;

    @UpdateTimestamp
    private Instant updatedAt;
}
