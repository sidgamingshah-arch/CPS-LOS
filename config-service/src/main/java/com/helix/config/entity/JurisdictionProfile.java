package com.helix.config.entity;

import com.helix.common.util.JsonAttributeConverters;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * A jurisdiction profile (PRD §10 sample). The regime-agnostic core consumes this;
 * all regime-specific logic lives in the referenced rule packs.
 */
@Entity
@Table(name = "jurisdiction_profiles")
@Getter
@Setter
public class JurisdictionProfile {

    @Id
    @Column(length = 20)
    private String code;            // e.g. IN-RBI, AE-CBUAE

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDate effectiveFrom;

    // ---- capital block ----
    @Column(nullable = false, length = 20)
    private String capitalApproach;     // SA | IRB_FOUNDATION

    @Column(nullable = false)
    private String capitalRuleset;      // rule-pack code, e.g. rbi_sa_directions_2026

    @Column(nullable = false)
    private String ecraMapping;         // rule-pack code

    private String odrAdjustment;       // rule-pack code

    @Column(nullable = false)
    private boolean dueDiligenceRequired;

    private boolean saccrEnabled;

    @Column(length = 20)
    private String cvaApproach;         // BA-CVA | SA-CVA

    // ---- provisioning block ----
    @Convert(converter = JsonAttributeConverters.StringListConverter.class)
    @Column(length = 500)
    private List<String> provisioningFrameworks;   // [ind_as_109, irac] | [ifrs_9]

    @Column(nullable = false)
    private String reportedProvisionPolicy;        // e.g. max(ecl,irac)

    private String sicrRules;                      // rule-pack code

    // ---- limits / kyc / reporting / residency ----
    private String exposureLimits;                 // rule-pack code
    private String kycCddRules;                    // rule-pack code
    private String reportingPack;
    private String dataResidency;                  // e.g. in-region-only

    @Column(nullable = false)
    private boolean active = true;
}
