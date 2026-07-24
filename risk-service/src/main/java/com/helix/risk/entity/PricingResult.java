package com.helix.risk.entity;

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
 * RAROC-based risk-adjusted price (PRD §7). Advisory only — never auto-applied;
 * below-hurdle deals are flagged with the gap for explicit human escalation.
 */
@Entity
@Table(name = "pricing_results", indexes = {
        @Index(name = "idx_pricing_app", columnList = "applicationReference")
})
@Getter
@Setter
public class PricingResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false)
    private double ead;

    private double expectedLoss;
    private double capitalCharge;
    private double costOfFundsAmount;
    private double opexAmount;

    private double recommendedRate;
    private double raroc;
    private double hurdleRaroc;
    private boolean belowHurdle;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> breakdown;

    /**
     * Additive dual-approach detail (never mutates the authoritative aggregate above): the
     * per-facility RAROC prices ({@code perFacility}), the resolved {@code hurdle} provenance, and
     * the {@code peer} benchmark price. Null on older rows / when nothing extra was computed, so the
     * authoritative recommendedRate/raroc/hurdleRaroc stay byte-identical to the historical shape.
     */
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 16000)
    private Map<String, Object> detail;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
