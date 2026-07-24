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
        pack("rbi_scorecard_v1", "SCORECARD", "IN-RBI", scorecard());
        pack("rbi_lgd_foundation", "LGD_MAP", "IN-RBI", lgdMap());
        pack("ind_as_109_sicr_v2", "PROVISIONING", "IN-RBI", provisioningIn());
        pack("rbi_doa_matrix", "DOA_MATRIX", "IN-RBI", doaMatrix(50_000_000d, 250_000_000d, 1_000_000_000d));
        pack("rbi_group_grade", "GROUP_GRADE", "IN-RBI", groupGrade());
        pack("rbi_kyc_md_tiers", "CDD_TIERS", "IN-RBI", cddTiers());
        pack("rbi_large_exposure_framework", "EXPOSURE_LIMITS", "IN-RBI", exposureLimits(0.15, 0.25));
        pack("rbi_concentration_limits", "CONCENTRATION_LIMITS", "IN-RBI", concentrationLimits(0.15, 0.25, 0.15));
        pack("rbi_pricing_v1", "PRICING", "IN-RBI", pricing(0.15, 0.075, 0.010));
        pack("workflow_mid_corp_rbi_v1", "WORKFLOW_DEFINITION", "IN-RBI",
                workflowMidCorporate("MID_CORPORATE", true));
        pack("workflow_sme_rbi_v1", "WORKFLOW_DEFINITION", "IN-RBI",
                workflowSme("SME"));
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
        pack("cbuae_scorecard", "SCORECARD", "AE-CBUAE", scorecard());
        pack("cbuae_lgd", "LGD_MAP", "AE-CBUAE", lgdMap());
        pack("ifrs_9_sicr_v1", "PROVISIONING", "AE-CBUAE", provisioningAe());
        pack("cbuae_doa_matrix", "DOA_MATRIX", "AE-CBUAE", doaMatrix(20_000_000d, 100_000_000d, 500_000_000d));
        pack("cbuae_group_grade", "GROUP_GRADE", "AE-CBUAE", groupGrade());
        pack("cbuae_aml_tiers", "CDD_TIERS", "AE-CBUAE", cddTiers());
        pack("cbuae_large_exposure", "EXPOSURE_LIMITS", "AE-CBUAE", exposureLimits(0.25, 0.25));
        pack("cbuae_concentration_limits", "CONCENTRATION_LIMITS", "AE-CBUAE", concentrationLimits(0.25, 0.25, 0.18));
        pack("cbuae_pricing", "PRICING", "AE-CBUAE", pricing(0.135, 0.045, 0.011));
        pack("workflow_mid_corp_cbuae_v1", "WORKFLOW_DEFINITION", "AE-CBUAE",
                workflowMidCorporate("MID_CORPORATE", false));
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

    /**
     * Statistical scorecard: weighted financial factors (linear band score in [0,100],
     * inverse = lower-is-better) + score→grade cut-points on the master scale. Values are
     * a verbatim lift of the constants the RatingEngine shipped with — the pack MUST
     * reproduce today's grades exactly (behaviour-preserving move from code to config).
     * source: RATIO reads the spread's ratio map; TREND reads the trends map.
     */
    private Map<String, Object> scorecard() {
        return map(
                "factors", List.of(
                        map("key", "NET_LEVERAGE", "weight", 0.22, "worst", 1.0, "best", 6.0,
                                "inverse", true, "source", "RATIO"),
                        map("key", "INTEREST_COVERAGE", "weight", 0.18, "worst", 1.0, "best", 6.0,
                                "inverse", false, "source", "RATIO"),
                        map("key", "DSCR", "weight", 0.18, "worst", 1.0, "best", 2.0,
                                "inverse", false, "source", "RATIO"),
                        map("key", "EBITDA_MARGIN", "weight", 0.15, "worst", 0.02, "best", 0.25,
                                "inverse", false, "source", "RATIO"),
                        map("key", "CURRENT_RATIO", "weight", 0.10, "worst", 0.8, "best", 2.0,
                                "inverse", false, "source", "RATIO"),
                        map("key", "GEARING", "weight", 0.10, "worst", 0.5, "best", 3.0,
                                "inverse", true, "source", "RATIO"),
                        map("key", "REVENUE_GROWTH", "weight", 0.07, "worst", -0.10, "best", 0.15,
                                "inverse", false, "source", "TREND")),
                "gradeCutoffs", List.of(
                        map("minScore", 90.0, "grade", "AAA"),
                        map("minScore", 82.0, "grade", "AA"),
                        map("minScore", 74.0, "grade", "A"),
                        map("minScore", 66.0, "grade", "BBB"),
                        map("minScore", 56.0, "grade", "BB"),
                        map("minScore", 46.0, "grade", "B"),
                        map("minScore", 36.0, "grade", "CCC"),
                        map("minScore", 26.0, "grade", "CC"),
                        map("minScore", 16.0, "grade", "C"),
                        map("minScore", 0.0, "grade", "D")));
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
                "reported_provision_policy", "max(ecl,irac)",
                // ---- RBI supervisory overlay (IN-RBI only; absent keys => current behaviour) ----
                // SMA sub-classification of standard accounts by DPD (SMA-0/1/2 -> NPA at substandard cut).
                "sma_enabled", 1,
                "sma_0_max_dpd", 30,
                "sma_1_max_dpd", 60,
                "sma_2_max_dpd", 90,
                // CRILC large-credit reporting threshold (aggregate exposure).
                "crilc_exposure_threshold", 50_000_000d,
                // Doubtful-asset age-banded provisioning on the SECURED portion; unsecured is 100%.
                "irac_doubtful_age_bands", map("D1", 0.25, "D2", 0.40, "D3", 1.00),
                "irac_doubtful_unsecured_rate", 1.00,
                "irac_doubtful_d1_max_dpd", 730,
                "irac_doubtful_d2_max_dpd", 1460,
                // Restructured accounts are held at (at least) SUB_STANDARD for a hold period.
                "restructure_npa_hold_months", 12,
                "restructure_classification_floor", "SUB_STANDARD",
                // Working-capital drawing-power monitoring margins (advisory).
                "drawing_power_enabled", 1,
                "dp_stock_margin_pct", 0.25,
                "dp_debtor_margin_pct", 0.40
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

    /** Group-grade rollup method (D10): exposure-weighted average notch on the AAA..D ladder. */
    private Map<String, Object> groupGrade() {
        return map(
                "method", "EXPOSURE_WEIGHTED_NOTCH",   // | WORST_OF | PARENT_ANCHORED
                "rounding", "HALF_UP_WORSE",           // | HALF_UP_BETTER
                "parent_support_notches", 0,
                "min_rated_members", 1
        );
    }

    /**
     * Delegated authority: amount × rating → approver level, now carrying the NAMED committee ladder
     * (Stage-4 sanctioning ladder). The {@code (max_amount, min_grade, authority)} routing keys and
     * the committee/quorum flags are UNCHANGED (behaviour-preserving); {@code committee_label} +
     * {@code composition} are additive display metadata surfaced on the decision + sanction letter.
     * The full PSB committee ladder (Regional/Zonal → Circle/FGM → HOCAC-I/II/III → MCB → Board) is
     * documented in {@code committee_ladder} for the governance view; the cross-cutting escalation
     * matrix and the three lines of defence ride alongside as config-as-data.
     */
    private Map<String, Object> doaMatrix(double l1, double l2, double l3) {
        return map(
                "levels", List.of(
                        map("max_amount", l1, "min_grade", "BBB", "authority", "RM_HEAD",
                                "committee_label", "Regional/Zonal Office Credit Committee",
                                "composition", "DGM/GM-chaired — smaller wholesale limits, renewals"),
                        map("max_amount", l2, "min_grade", "BB", "authority", "CREDIT_OFFICER",
                                "committee_label", "Circle / FGM-level Credit Committee",
                                "composition", "GM/CGM-chaired — mid-corporate range"),
                        map("max_amount", l3, "min_grade", "B", "authority", "CREDIT_COMMITTEE",
                                "committee_label", "Head-Office Credit Approval Committee (HOCAC-I/II)",
                                "composition", "CGM/ED-chaired, with risk & finance — large corporate"),
                        map("max_amount", Double.MAX_VALUE, "min_grade", "D", "authority", "BOARD_COMMITTEE",
                                "committee_label", "HOCAC-III / Management Committee of the Board / Board",
                                "composition", "MD & CEO-chaired, escalating to the Board — largest exposures")
                ),
                "deviation_escalates_one_level", true,
                "below_hurdle_requires_escalation", true,
                // New-to-bank obligors weaker than this grade escalate one authority tier with CRO
                // concurrence (Stage-4 hurdle-rating norm). Config-driven; the router honours it.
                "hurdle_grade", "BB",
                // Full PSB committee ladder (reference for the governance view; monetary spans are
                // bank-internal and set via the tiers above). Private banks run the joint
                // business-risk equivalent escalating to a Senior Credit Committee + Committee of Directors.
                "committee_ladder", List.of(
                        map("tier", "Regional/Zonal Office Credit Committee", "chair", "DGM/GM",
                                "span", "Smaller wholesale limits, renewals"),
                        map("tier", "Circle / FGM-level Credit Committee", "chair", "GM/CGM",
                                "span", "Mid-corporate range"),
                        map("tier", "HOCAC-I", "chair", "CGM, with risk & finance", "span", "Large corporate, lower band"),
                        map("tier", "HOCAC-II", "chair", "ED", "span", "Large corporate, upper band"),
                        map("tier", "HOCAC-III", "chair", "MD & CEO", "span", "Largest within MD's delegated powers"),
                        map("tier", "Management Committee of the Board (MCB)", "chair", "MD, EDs, directors",
                                "span", "Beyond MD's powers"),
                        map("tier", "Board / Board Credit Committee", "chair", "Full board",
                                "span", "Policy, ratification, review of sanctions")
                ),
                // Cross-cutting escalation matrix (Stage-4 / cross-cutting): trigger -> escalates_to.
                "escalation_matrix", List.of(
                        map("trigger", "Quantum exceeds tier's DoP", "escalates_to", "Next committee tier (the ladder above)"),
                        map("trigger", "Rating below hurdle / rating override", "escalates_to",
                                "One level above normal sanctioning tier + CRO concurrence"),
                        map("trigger", "Policy deviation (LTV, tenor, unsecured, pricing concession)",
                                "escalates_to", "Deviation grid — typically one tier up"),
                        map("trigger", "Group exposure nearing internal / LEF cap", "escalates_to",
                                "HOCAC-III / MCB with risk vetting"),
                        map("trigger", "Any sanction exercised", "escalates_to",
                                "Post-facto review by the next higher authority / MCB / Board")
                ),
                // Three lines of defence (role -> line) — coverage originates, credit/risk concurs, audit reviews.
                "lines_of_defence", map(
                        "RM", "FIRST", "RM_HEAD", "FIRST", "ANALYST", "FIRST",
                        "CREDIT_OPS", "SECOND", "CREDIT_OFFICER", "SECOND", "CREDIT_COMMITTEE", "SECOND",
                        "CRO", "SECOND", "COMPLIANCE", "SECOND", "PORTFOLIO", "SECOND",
                        "LEGAL", "SECOND", "CAD_OPS", "SECOND",
                        "BOARD_COMMITTEE", "OVERSIGHT")
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

    /**
     * Multi-dimensional concentration thresholds. Each dimension carries its limit
     * basis (CAPITAL or PORTFOLIO) and limit %. The intersection cells
     * (sector × geography, rating × sector) are deliberately tight — they are where
     * correlated tail risk hides. {@code singleName}/{@code group} flow through so a
     * jurisdiction's large-exposure stance is consistent across both views.
     */
    private Map<String, Object> concentrationLimits(double singleName, double group, double sectorGeoCell) {
        return map(
                "capital_base", 50_000_000_000d,
                // Early-warning bands: NORMAL < 80% ≤ WATCH < 90% ≤ WARNING < 100% ≤ BREACH.
                "watch_pct", 0.80,
                "warning_pct", 0.90,
                // Capital buffer the correlation-stress engine rolls stressed loss against.
                "capital_buffer_pct", 0.10,
                "dimensions", map(
                        "SINGLE_NAME", map("basis", "CAPITAL", "limitPct", singleName),
                        "GROUP", map("basis", "CAPITAL", "limitPct", group),
                        "SECTOR", map("basis", "PORTFOLIO", "limitPct", 0.20),
                        "GEOGRAPHY", map("basis", "PORTFOLIO", "limitPct", 0.40),
                        "INSTRUMENT", map("basis", "PORTFOLIO", "limitPct", 0.50),
                        "DURATION_BUCKET", map("basis", "PORTFOLIO", "limitPct", 0.45),
                        "RATING", map("basis", "PORTFOLIO", "limitPct", 0.35),
                        "CURRENCY", map("basis", "PORTFOLIO", "limitPct", 0.60),
                        "SECTOR_x_GEOGRAPHY", map("basis", "PORTFOLIO", "limitPct", sectorGeoCell),
                        "RATING_x_SECTOR", map("basis", "PORTFOLIO", "limitPct", 0.12)),
                // Sector co-movement matrix for correlation-stress: ρ of each sector to a
                // shock in the row sector. Symmetric in spirit; only the shocked row is read.
                "correlations", sectorCorrelations());
    }

    /**
     * A pragmatic sector correlation matrix for the demo book. The clusters that matter:
     * the real-estate / construction / steel / infrastructure / power complex moves
     * together (a property or capex downturn hits all of them), while retail / trade /
     * logistics form a looser consumer cluster.
     */
    private Map<String, Object> sectorCorrelations() {
        return map(
                "INFRASTRUCTURE", map("CONSTRUCTION", 0.80, "STEEL", 0.70, "POWER", 0.65,
                        "REAL_ESTATE", 0.55, "MANUFACTURING", 0.45, "LOGISTICS", 0.40,
                        "RETAIL", 0.20, "TRADE", 0.20),
                "REAL_ESTATE", map("CONSTRUCTION", 0.85, "STEEL", 0.60, "INFRASTRUCTURE", 0.55,
                        "MANUFACTURING", 0.35, "RETAIL", 0.30, "POWER", 0.30,
                        "LOGISTICS", 0.25, "TRADE", 0.20),
                "MANUFACTURING", map("STEEL", 0.65, "LOGISTICS", 0.55, "TRADE", 0.50,
                        "INFRASTRUCTURE", 0.45, "CONSTRUCTION", 0.40, "RETAIL", 0.40,
                        "POWER", 0.35, "REAL_ESTATE", 0.35),
                "RETAIL", map("TRADE", 0.75, "LOGISTICS", 0.60, "MANUFACTURING", 0.40,
                        "REAL_ESTATE", 0.30, "INFRASTRUCTURE", 0.20),
                "TRADE", map("RETAIL", 0.75, "LOGISTICS", 0.65, "MANUFACTURING", 0.50,
                        "INFRASTRUCTURE", 0.20),
                "LOGISTICS", map("TRADE", 0.65, "RETAIL", 0.60, "MANUFACTURING", 0.55,
                        "INFRASTRUCTURE", 0.40),
                "STEEL", map("INFRASTRUCTURE", 0.70, "MANUFACTURING", 0.65, "CONSTRUCTION", 0.65,
                        "REAL_ESTATE", 0.60, "POWER", 0.45),
                "CONSTRUCTION", map("REAL_ESTATE", 0.85, "INFRASTRUCTURE", 0.80, "STEEL", 0.65,
                        "MANUFACTURING", 0.40, "POWER", 0.40),
                "POWER", map("INFRASTRUCTURE", 0.65, "STEEL", 0.45, "CONSTRUCTION", 0.40,
                        "MANUFACTURING", 0.35, "REAL_ESTATE", 0.30));
    }

    /**
     * Workflow definition consumed by the orchestration layer — each stage has an
     * autonomy level, a human gate flag, and an SLA. New segments / jurisdictions
     * tune these without code changes (PRD configurability NFR).
     */
    private Map<String, Object> workflowMidCorporate(String segment, boolean ddRequired) {
        return map("segment", segment, "stages", List.of(
                stage("INTAKE", "Application intake & document classification", "A", true, false, 4),
                stage("KYC_CDD", "KYC/CDD · UBO · screening", "C", true, true, 24),
                stage("SPREADING", "Financial spreading with provenance", "C", true, false, 12),
                stage("SPREAD_CONFIRM", "Analyst confirms canonical spread", "—", false, true, 4),
                stage("RATING", "Scorecard rating with notch-limited overrides", "D", true, true, 8),
                stage("CAPITAL_PROJECTION", "Capital projection for RAROC (internal)", "—", false, false, 1),
                stage("PRICING", "RAROC-based risk-adjusted pricing (advisory)", "D", true, false, 1),
                stage("CREDIT_PROPOSAL", "Generate credit memo (grounded, cited)", "C", true, false, 2),
                stage("APPROVAL", "DoA routing & named-human decision", "—", false, true,
                        ddRequired ? 72 : 48),
                stage("DOCUMENTATION", "Sanction & security documentation · CP/CS", "C", true, true, 48),
                stage("LIMIT_SETUP_BOOKING", "Limit setup · core-banking booking", "—", false, true, 8),
                stage("MONITORING", "Covenant testing · EWS · review triggers", "A", true, true, 0),
                stage("ECL_PROVISIONING", "Periodic ECL/IRAC (parallel to bank engine)", "—", false, false, 0),
                stage("RAROC_TRACKING", "Projected vs actual RAROC variance", "—", false, false, 0)
        ));
    }

    private Map<String, Object> workflowSme(String segment) {
        // STP-eligible clean SME path (PRD §3.3) — fewer manual touch-points; clean
        // names still require a named human at the approval gate.
        return map("segment", segment, "stp_eligible", true, "stages", List.of(
                stage("INTAKE", "Application intake & document classification", "A", true, false, 1),
                stage("KYC_CDD", "Simplified KYC (where eligible)", "C", true, true, 8),
                stage("SPREADING", "Templated spreading + alt-data", "C", true, false, 2),
                stage("SPREAD_CONFIRM", "Analyst confirms canonical spread", "—", false, true, 1),
                stage("RATING", "Templated scorecard", "D", true, true, 1),
                stage("CAPITAL_PROJECTION", "Capital projection for RAROC", "—", false, false, 1),
                stage("PRICING", "RAROC-based pricing", "D", true, false, 1),
                stage("APPROVAL", "DoA routing — STP candidate, human signs", "—", false, true, 4),
                stage("LIMIT_SETUP_BOOKING", "Limit setup · booking", "—", false, true, 2),
                stage("MONITORING", "Covenants · EWS", "A", true, true, 0)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stage(String key, String label, String autonomy,
                                             boolean ai, boolean humanGate, int slaHours) {
        return map("key", key, "label", label, "autonomy", autonomy, "ai", ai,
                "humanGate", humanGate, "slaHours", slaHours);
    }

    private Map<String, Object> pricing(double hurdleRaroc, double costOfFunds, double opexRate) {
        return map(
                "hurdle_raroc", hurdleRaroc,
                "cost_of_funds", costOfFunds,
                "opex_rate", opexRate,
                "target_capital_ratio", 0.12,
                "min_spread", 0.005,
                "exception_single_level_bps", 100,
                "exception_two_level_bps", 200
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
        p.setCreatedBy("seed");   // G6: seeded packs authored by 'seed' (distinct from every signer)
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
