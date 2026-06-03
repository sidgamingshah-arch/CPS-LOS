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
 * Deterministic RWA & capital result (PRD §6). No generative AI in this path —
 * every figure traces to its inputs and the rule-pack version used.
 */
@Entity
@Table(name = "capital_results", indexes = {
        @Index(name = "idx_capital_app", columnList = "applicationReference")
})
@Getter
@Setter
public class CapitalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 20)
    private String jurisdiction;

    @Column(nullable = false, length = 40)
    private String exposureClass;

    @Column(nullable = false)
    private double ead;

    private double baseRiskWeight;
    private double appliedRiskWeight;
    private boolean dueDiligenceUpliftApplied;

    private double collateralHaircut;
    private double securedPortion;
    private double unsecuredPortion;

    @Column(nullable = false)
    private double rwa;

    @Column(nullable = false)
    private double capitalRequired;

    private double capitalRatio;

    // ---- provenance: which rule-pack version produced this (US-6.1/6.2) ----
    private String capitalPackCode;
    private int capitalPackVersion;
    private String ecraPackCode;
    private int ecraPackVersion;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> trace;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
