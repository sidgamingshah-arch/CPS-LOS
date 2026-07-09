package com.helix.portfolio.entity;

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

import java.time.Instant;
import java.util.Map;

/**
 * Working-capital drawing-power assessment from a borrowing-base / stock statement
 * (RBI DP norms). Advisory + deterministic: DP = stock×(1−stockMargin) +
 * debtors×(1−debtorMargin) − creditors, capped at the sanctioned limit. It flags a
 * shortfall when the outstanding exceeds DP but never mutates the limit ledger —
 * the authoritative utilisation figure is unchanged (the AI/advisory-vs-authoritative
 * split, here with a deterministic advisory output).
 */
@Entity
@Table(name = "drawing_power_assessments", indexes = {
        @Index(name = "idx_dp_app_facility", columnList = "applicationReference,facilityRef")
})
@Getter
@Setter
public class DrawingPowerAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String applicationReference;

    @Column(nullable = false, length = 60)
    private String facilityRef;

    private double stock;
    private double debtors;
    private double creditors;
    private double stockMarginPct;
    private double debtorMarginPct;

    private double drawingPower;
    private double sanctionedLimit;
    private double outstanding;
    private double shortfall;
    private boolean capped;

    @Column(nullable = false)
    private boolean advisory = true;

    private String currency;
    private String provisioningPackCode;
    private int provisioningPackVersion;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 2000)
    private Map<String, Object> components;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
