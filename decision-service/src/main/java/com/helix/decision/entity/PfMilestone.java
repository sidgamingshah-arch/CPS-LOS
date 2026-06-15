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
 * A project-finance construction milestone. PF facilities draw down in tranches
 * against physical progress, and each tranche may only be released once the
 * Lender's Independent Engineer (LIE) certifies the milestone. This is the
 * per-tranche analogue of the pre-disbursement CP gate: CPs clear once before the
 * first draw; a milestone must be LIE-certified before <i>its</i> tranche draws.
 *
 * <pre>PLANNED → LIE_CERTIFIED → DRAWN</pre>
 */
@Entity
@Table(name = "pf_milestones", indexes = {
        @Index(name = "idx_pfms_app_facility", columnList = "applicationReference,facilityRef"),
        @Index(name = "idx_pfms_seq", columnList = "applicationReference,facilityRef,sequence")
})
@Getter
@Setter
public class PfMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String facilityRef;

    /** 1-based ordering; a drawdown names the milestone sequence it draws against. */
    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, precision = 22, scale = 2)
    private java.math.BigDecimal plannedAmount = com.helix.common.money.Money.ZERO;

    @Column(nullable = false, length = 8)
    private String currency;

    /** Target date for this milestone — drives the milestone-schedule view. */
    private java.time.LocalDate plannedDate;

    /** PLANNED · LIE_CERTIFIED · DRAWN */
    @Column(nullable = false, length = 20)
    private String status = "PLANNED";

    @Column(length = 80)  private String lieCertifiedBy;
    private Instant lieCertifiedAt;
    @Column(length = 200) private String certificationRef;

    /** The disbursement id that drew this milestone, once released. */
    private Long drawnByDisbursementId;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
