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
 * IFRS 9 / Ind AS 109 ECL with a parallel IRAC view (PRD §12, US-12.2). The
 * reported provision follows the jurisdiction's policy (e.g. max(ECL, IRAC)).
 * Deterministic — no AI sets a provision or a stage.
 */
@Entity
@Table(name = "ecl_results", indexes = {
        @Index(name = "idx_ecl_app", columnList = "applicationReference")
})
@Getter
@Setter
public class EclResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String applicationReference;

    @Column(nullable = false, length = 10)
    private String stage;              // STAGE_1 | STAGE_2 | STAGE_3

    private double pd12m;
    private double pdLifetime;
    private double lgd;
    private double ead;
    private double macroOverlay;
    private double ecl;

    // ---- parallel IRAC view ----
    private String iracClass;          // STANDARD | SUB_STANDARD | DOUBTFUL | LOSS
    private double iracProvisionRate;
    private double iracProvision;

    // ---- RBI supervisory overlay (IN-RBI only; null/false when the pack keys are absent) ----
    /** SMA sub-class of a standard account: NONE | SMA_0 | SMA_1 | SMA_2 (null when disabled). */
    @Column(length = 6)
    private String smaClass;
    /** Doubtful age band D1/D2/D3 when the age-banded provisioning path applies. */
    @Column(length = 4)
    private String doubtfulAgeBand;
    private double securedPortion;
    private double unsecuredPortion;
    private double securedProvision;
    private double unsecuredProvision;
    /** True when the restructure classification floor lifted stage/IRAC above the DPD-implied level. */
    private boolean restructureFloorApplied;

    @Column(nullable = false)
    private double reportedProvision;
    private String reportedProvisionPolicy;

    private String provisioningPackCode;
    private int provisioningPackVersion;

    @Convert(converter = JsonAttributeConverters.MapConverter.class)
    @Column(length = 8000)
    private Map<String, Object> trace;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;
}
