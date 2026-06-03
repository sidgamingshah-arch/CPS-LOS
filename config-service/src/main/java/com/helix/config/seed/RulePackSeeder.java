package com.helix.config.seed;

import com.helix.config.entity.JurisdictionProfile;
import com.helix.config.entity.RulePack;
import com.helix.config.repo.JurisdictionProfileRepository;
import com.helix.config.repo.RulePackRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds two go-live regimes (PRD assumptions box): IN-RBI and AE-CBUAE.
 * Demonstrates the core thesis — a new regime is an overlay (different rule-pack
 * data), never a code change. Seeded packs are pre-signed by both control functions.
 */
@Component
public class RulePackSeeder implements CommandLineRunner {

    private final JurisdictionProfileRepository profiles;
    private final RulePackRepository rulePacks;

    public RulePackSeeder(JurisdictionProfileRepository profiles, RulePackRepository rulePacks) {
        this.profiles = profiles;
        this.rulePacks = rulePacks;
    }

    @Override
    public void run(String... args) {
        if (profiles.count() > 0) {
            return;
        }
        seedRbi();
        seedCbuae();
    }

    // ------------------------------------------------------------------ IN-RBI

    private void seedRbi() {
        JurisdictionProfile in = new JurisdictionProfile();
        in.setCode("IN-RBI");
        in.setName("India — Reserve Bank of India (SA Directions 2026 / IRAC)");
        in.setEffectiveFrom(LocalDate.of(2027, 4, 1));
        in.setCapitalApproach("SA");
        in.setCapitalRuleset("rbi_sa_directions_2026");
        in.setEcraMapping("rbi_ecra_2026");
        in.setOdrAdjustment("rbi_odr_band");
        in.setDueDiligenceRequired(true);
        in.setSaccrEnabled(true);
        in.setCvaApproach("BA-CVA");
        in.setProvisioningFrameworks(List.of("ind_as_109", "irac"));
        in.setReportedProvisionPolicy("max(ecl,irac)");
        in.setSicrRules("ind_as_109_sicr_v2");
        in.setExposureLimits("rbi_large_exposure_framework");
        in.setKycCddRules("rbi_kyc_md_tiers");
        in.setReportingPack("rbi_returns_2027");
        in.setDataResidency("in-region-only");
        profiles.save(in);

        pack("rbi_sa_directions_2026", "CAPITAL_SA", "IN-RBI", capitalSa(0.0, 1.0));
        pack("rbi_ecra_2026", "ECRA_MAPPING", "IN-RBI", ecraMapping());
        pack("rbi_rating_pd_v3", "RATING_PD_MAP", "IN-RBI", ratingPdMap());
        pack("rbi_lgd_foundation", "LGD_MAP", "IN-RBI", lgdMap());
        pack("ind_as_109_sicr_v2", "PROVISIONING", "IN-RBI", provisioningIn());
        pack("rbi_doa_matrix", "DOA_MATRIX", "IN-RBI", doaMatrix(50_000_000d, 250_000_000d, 1_000_000_000d));
        pack("rbi_kyc_md_tiers", "CDD_TIERS", "IN-RBI", cddTiers());
        pack("rbi_large_exposure_framework", "EXPOSURE_LIMITS", "IN-RBI", exposureLimits(0.15, 0.25));
        pack("rbi_pricing_v1", "PRICING", "IN-RBI", pricing(0.15, 0.075, 0.010));
    }

    // --------------------------------------------------------------- AE-CBUAE

    private void seedCbuae() {
        JurisdictionProfile ae = new JurisdictionProfile();
        ae.setCode("AE-CBUAE");
        ae.setName("UAE — Central Bank of the UAE (Basel III)");
        ae.setEffectiveFrom(LocalDate.of(2024, 1, 1));
        ae.setCapitalApproach("SA");
        ae.setCapitalRuleset("cbuae_sa_basel3");
        ae.setEcraMapping("cbuae_ecra");
        ae.setOdrAdjustment(null);
        ae.setDueDiligenceRequired(false);
        ae.setSaccrEnabled(true);
        ae.setCvaApproach("SA-CVA");
        ae.setProvisioningFrameworks(List.of("ifrs_9"));
        ae.setReportedProvisionPolicy("ecl");
        ae.setSicrRules("ifrs_9_sicr_v1");
        ae.setExposureLimits("cbuae_large_exposure");
        ae.setKycCddRules("cbuae_aml_tiers");
        ae.setReportingPack("cbuae_returns");
        ae.setDataResidency("in-region-only");
        profiles.save(ae);

        // Same engine, different numbers — overlay, not release.
        pack("cbuae_sa_basel3", "CAPITAL_SA", "AE-CBUAE", capitalSa(0.0, 1.0));
        pack("cbuae_ecra", "ECRA_MAPPING", "AE-CBUAE", ecraMapping());
        pack("cbuae_rating_pd", "RATING_PD_MAP", "AE-CBUAE", ratingPdMap());
        pack("cbuae_lgd", "LGD_MAP", "AE-CBUAE", lgdMap());
        pack("ifrs_9_sicr_v1", "PROVISIONING", "AE-CBUAE", provisioningAe());
        pack("cbuae_doa_matrix", "DOA_MATRIX", "AE-CBUAE", doaMatrix(20_000_000d, 100_000_000d, 500_000_000d));
        pack("cbuae_aml_tiers", "CDD_TIERS", "AE-CBUAE", cddTiers());
        pack("cbuae_large_exposure", "EXPOSURE_LIMITS", "AE-CBUAE", exposureLimits(0.25, 0.25));
        pack("cbuae_pricing", "PRICING", "AE-CBUAE", pricing(0.135, 0.045, 0.011));
    }

    // ------------------------------------------------------------ pack content

    /** Standardised-approach risk weights for UNRATED exposures + CCF + CRM haircuts. */
    private Map<String, Object> capitalSa(double domesticSovereignRw, double scale) {
        Map<String, Object> unratedRw = map(
                "SOVEREIGN", domesticSovereignRw,
                "BANK", 0.40,
                "CORPORATE", 1.00,
                "SME_CORPORATE", 0.85,     // SME supporting factor
                "SPECIALISED_LENDING", 1.00,
                "EQUITY", 2.50,
                "OTHER", 1.00
        );
        // Supervisory slotting categories for specialised (project) lending.
        Map<String, Object> slotting = map(
                "STRONG", 0.70,
                "GOOD", 0.90,
                "SATISFACTORY", 1.15,
                "WEAK", 2.50,
                "DEFAULT", 0.00
        );
        return map(
                "unrated_risk_weights", unratedRw,
                "below_investment_grade_corporate_rw", 1.50,
                "slotting_risk_weights", slotting,
                "ccf_undrawn_commitment", 0.40,
                "ccf_trade_contingent", 0.20,
                "ccf_direct_credit_substitute", 1.00,
                "crm_collateral_haircuts", map(
                        "CASH", 0.00,
                        "GOVT_SECURITIES", 0.02,
                        "EQUITY_LISTED", 0.25,
                        "PROPERTY", 0.40,
                        "RECEIVABLES", 0.50
                ),
                "due_diligence_uplift", 0.25,   // applied to unrated where DD evidence absent
                "capital_ratio_min", 0.09       // total capital requirement on RWA
        );
    }

    /** External agency grade bucket → risk weight (corporate & bank tables). */
    private Map<String, Object> ecraMapping() {
        Map<String, Object> corporate = map(
                "AAA_AA", 0.20,
                "A", 0.50,
                "BBB", 1.00,
                "BB", 1.00,
                "B_AND_BELOW", 1.50,
                "UNRATED", 1.00
        );
        Map<String, Object> bank = map(
                "AAA_AA", 0.20,
                "A", 0.30,
                "BBB", 0.50,
                "BB", 1.00,
                "B_AND_BELOW", 1.50,
                "UNRATED", 0.40
        );
        return map("corporate", corporate, "bank", bank,
                "internal_grade_to_bucket", map(
                        "AAA", "AAA_AA", "AA", "AAA_AA", "A", "A",
                        "BBB", "BBB", "BB", "BB",
                        "B", "B_AND_BELOW", "CCC", "B_AND_BELOW", "CC", "B_AND_BELOW",
                        "C", "B_AND_BELOW", "D", "B_AND_BELOW"));
    }

    /** Internal master-scale grade → through-the-cycle PD. */
    private Map<String, Object> ratingPdMap() {
        return map(
                "AAA", 0.0003, "AA", 0.0005, "A", 0.0010,
                "BBB", 0.0030, "BB", 0.0100, "B", 0.0350,
                "CCC", 0.1200, "CC", 0.2500, "C", 0.4000, "D", 1.0000
        );
    }

    /** Foundation LGD by seniority/collateralisation. */
    private Map<String, Object> lgdMap() {
        return map(
                "SENIOR_UNSECURED", 0.45,
                "SENIOR_SECURED", 0.25,
                "SUBORDINATED", 0.75,
                "FULLY_COLLATERALISED", 0.15
        );
    }

    private Map<String, Object> provisioningIn() {
        return map(
                "sicr_dpd_stage2", 30,
                "sicr_dpd_stage3", 90,
                "sicr_notch_downgrade_stage2", 3,
                "ecl_macro_overlay", 1.10,
                "irac_provision_rates", map(
                        "STANDARD", 0.0040,
                        "SUB_STANDARD", 0.15,
                        "DOUBTFUL", 0.40,
                        "LOSS", 1.00
                ),
                "irac_dpd_substandard", 90,
                "irac_dpd_doubtful", 365,
                "reported_provision_policy", "max(ecl,irac)"
        );
    }

    private Map<String, Object> provisioningAe() {
        return map(
                "sicr_dpd_stage2", 30,
                "sicr_dpd_stage3", 90,
                "sicr_notch_downgrade_stage2", 3,
                "ecl_macro_overlay", 1.05,
                "irac_provision_rates", Map.of(),
                "reported_provision_policy", "ecl"
        );
    }

    /** Delegated authority: amount × rating → approver level. */
    private Map<String, Object> doaMatrix(double l1, double l2, double l3) {
        return map(
                "levels", List.of(
                        map("max_amount", l1, "min_grade", "BBB", "authority", "RM_HEAD"),
                        map("max_amount", l2, "min_grade", "BB", "authority", "CREDIT_OFFICER"),
                        map("max_amount", l3, "min_grade", "B", "authority", "CREDIT_COMMITTEE"),
                        map("max_amount", Double.MAX_VALUE, "min_grade", "D", "authority", "BOARD_COMMITTEE")
                ),
                "deviation_escalates_one_level", true,
                "below_hurdle_requires_escalation", true
        );
    }

    private Map<String, Object> cddTiers() {
        return map(
                "default_tier", "STANDARD",
                "enhanced_triggers", List.of("PEP", "HIGH_RISK_JURISDICTION", "ADVERSE_MEDIA", "COMPLEX_OWNERSHIP"),
                "simplified_eligible", List.of("LISTED_ENTITY", "REGULATED_FI"),
                "ubo_threshold_pct", 10.0,
                "rekyc_months", map("ENHANCED", 12, "STANDARD", 24, "SIMPLIFIED", 36)
        );
    }

    private Map<String, Object> exposureLimits(double singleName, double group) {
        return map(
                "single_name_pct_capital", singleName,
                "connected_group_pct_capital", group,
                "sector_cap_pct_portfolio", 0.20,
                "geography_cap_pct_portfolio", 0.30,
                "capital_base", 50_000_000_000d
        );
    }

    private Map<String, Object> pricing(double hurdleRaroc, double costOfFunds, double opexRate) {
        return map(
                "hurdle_raroc", hurdleRaroc,
                "cost_of_funds", costOfFunds,
                "opex_rate", opexRate,
                "target_capital_ratio", 0.12,
                "min_spread", 0.005
        );
    }

    // --------------------------------------------------------------- helpers

    private void pack(String code, String type, String jurisdiction, Map<String, Object> payload) {
        RulePack p = new RulePack();
        p.setCode(code);
        p.setType(type);
        p.setJurisdiction(jurisdiction);
        p.setVersion(1);
        p.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        p.setActive(true);
        p.setPayload(payload);
        // Seed data ships pre-approved by both control functions.
        p.setPolicySignedOffBy("seed.policy.officer");
        p.setPolicySignedOffAt(Instant.now());
        p.setModelRiskSignedOffBy("seed.model.risk");
        p.setModelRiskSignedOffAt(Instant.now());
        rulePacks.save(p);
    }

    private static Map<String, Object> map(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of args");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
