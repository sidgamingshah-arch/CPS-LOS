package com.helix.limit.entity;

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

import java.time.Instant;
import java.time.LocalDate;

/**
 * Financial-institution transaction submitted from a product processor for FI
 * department approval (PRD FI Standalone & its workflow + transaction processing).
 * It carries its own workflow; on approval it utilises the obligor's facility line.
 */
@Entity
@Table(name = "fi_transactions", indexes = {
        @Index(name = "idx_fitx_cif", columnList = "cif")
})
@Getter
@Setter
public class FiTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String fid;                 // platform-generated FID

    @Column(nullable = false, length = 30)
    private String cif;

    @Column(nullable = false, length = 5)
    private String country;
    @Column(nullable = false, length = 30)
    private String department = "FI";

    @Column(nullable = false, length = 30)
    private String lineId;              // limit-tree line under the FI

    @Column(nullable = false, length = 40)
    private String facilityType;

    @Column(nullable = false)
    private double amount;
    @Column(nullable = false, length = 5)
    private String currency;
    private double baseAmount;

    private LocalDate maturityDate;
    private double cashMargin;
    private boolean breachesLimit;

    private String productProcessor;
    private String bookingUnit;
    private String transactionRef;

    @Column(nullable = false, length = 20)
    private String status;              // PENDING_APPROVAL | APPROVED | REJECTED | EXCEPTION_APPROVED

    private String submittedBy;         // maker: actor who raised the tx (SoD source vs approver)
    private String approvedBy;
    private String rejectedReason;
    private Double approvedRate;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant submittedAt;
    private Instant decidedAt;
}
