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
import java.util.List;

/**
 * A spoke on an SCF programme — a supplier (VENDOR programme) or distributor
 * (DEALER programme) of the anchor. Its {@code requestedAmount} is judged against the
 * programme's pinned {@code SCF_ELIGIBILITY} snapshot + per-spoke cap deterministically;
 * the {@code eligibilityResult} (PASS | FAIL) and human-readable {@code reasons} are the
 * output of that deterministic engine, and {@code approvedCap} is the cap granted on a PASS.
 */
@Entity
@Table(name = "scf_spokes", indexes = {
        @Index(name = "idx_scf_spoke_prog", columnList = "programRef"),
        @Index(name = "idx_scf_spoke_ref", columnList = "spokeRef")
})
@Getter
@Setter
public class ScfSpoke {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String programRef;             // owning ScfProgram scfRef

    @Column(nullable = false, length = 40)
    private String spokeRef;               // spoke counterparty reference

    @Column(length = 200)
    private String spokeName;

    @Column(name = "requested_amount", nullable = false)
    private double requestedAmount;

    @Column(nullable = false, length = 10)
    private String eligibilityResult;      // PASS | FAIL

    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 4000)
    private List<String> reasons;

    @Column(name = "approved_cap", nullable = false)
    private double approvedCap;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
