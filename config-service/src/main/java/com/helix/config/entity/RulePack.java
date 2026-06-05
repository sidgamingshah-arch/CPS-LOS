package com.helix.config.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * A versioned, effective-dated rule pack (PRD §10). Changes require dual sign-off
 * (policy + model risk) before they may be activated.
 */
@Entity
@Table(name = "rule_packs", indexes = {
        @Index(name = "idx_rulepack_lookup", columnList = "jurisdiction,type,active")
})
@Getter
@Setter
public class RulePack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String code;            // e.g. rbi_sa_directions_2026

    @Column(nullable = false, length = 40)
    private String type;            // CAPITAL_SA, ECRA_MAPPING, PROVISIONING, DOA_MATRIX, ...

    @Column(nullable = false, length = 20)
    private String jurisdiction;    // IN-RBI, AE-CBUAE, GLOBAL

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false)
    private boolean active;

    /** Structured rule content, consumed by downstream engines. */
    @Lob
    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(nullable = false, length = 16000)
    private Map<String, Object> payload;

    // ---- dual sign-off governance (PRD §10/§11) ----
    private String policySignedOffBy;
    private Instant policySignedOffAt;
    private String modelRiskSignedOffBy;
    private Instant modelRiskSignedOffAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** A pack is governance-approved only when both control functions have signed. */
    public boolean isFullySignedOff() {
        return policySignedOffBy != null && modelRiskSignedOffBy != null;
    }
}
