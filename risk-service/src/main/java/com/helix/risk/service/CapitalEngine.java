package com.helix.risk.service;

import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.CreditInputsDto.CollateralInput;
import com.helix.risk.dto.CreditInputsDto.FacilityInput;
import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.CapitalResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic Standardised-Approach RWA & capital engine (PRD §6). Selects the
 * exposure class, applies the ECRA-mapped risk weight (or supervisory slotting for
 * specialised lending), recognises CRM, applies any due-diligence uplift, and emits
 * a full trace so every figure reconciles to its inputs and rule-pack versions.
 * No generative AI participates in this computation.
 */
@Component
public class CapitalEngine {

    public CapitalResult compute(CreditInputsDto in, String finalGrade, double nominalEad,
                                 boolean dueDiligenceRequired, boolean dueDiligenceDone,
                                 RulePackDto capPack, RulePackDto ecraPack) {
        String exposureClass = exposureClass(in.segment(), in.facilityType());
        double ccf = ccf(in.facilityType(), capPack);
        double exposure = nominalEad * ccf;

        double baseRw = baseRiskWeight(exposureClass, finalGrade, in, capPack, ecraPack);

        boolean upliftApplied = dueDiligenceRequired && !dueDiligenceDone;
        double ddUplift = upliftApplied ? capPack.number("due_diligence_uplift", 0.25) : 0.0;
        double appliedRw = baseRw + ddUplift;

        // Credit risk mitigation (comprehensive, simplified): the covered portion is
        // mitigated after the supervisory haircut; the residual carries the full weight.
        double haircut = 0.0;
        double securedPortion = 0.0;
        if (in.secured() && in.collateralValue() > 0 && in.collateralType() != null) {
            haircut = readDouble(capPack.map("crm_collateral_haircuts"), in.collateralType().toUpperCase(), 0.5);
            double coverage = in.collateralValue() * (1 - haircut);
            securedPortion = Math.min(exposure, coverage);
        }
        double unsecuredPortion = exposure - securedPortion;
        double rwa = unsecuredPortion * appliedRw;   // covered portion treated as fully mitigated
        double capitalRatio = capPack.number("capital_ratio_min", 0.09);
        double capital = rwa * capitalRatio;

        CapitalResult r = new CapitalResult();
        r.setApplicationReference(in.applicationReference());
        r.setJurisdiction(in.jurisdiction());
        r.setExposureClass(exposureClass);
        r.setEad(round(exposure));
        r.setBaseRiskWeight(baseRw);
        r.setAppliedRiskWeight(appliedRw);
        r.setDueDiligenceUpliftApplied(upliftApplied);
        r.setCollateralHaircut(haircut);
        r.setSecuredPortion(round(securedPortion));
        r.setUnsecuredPortion(round(unsecuredPortion));
        r.setRwa(round(rwa));
        r.setCapitalRequired(round(capital));
        r.setCapitalRatio(capitalRatio);
        r.setCapitalPackCode(capPack.code());
        r.setCapitalPackVersion(capPack.version());
        r.setEcraPackCode(ecraPack.code());
        r.setEcraPackVersion(ecraPack.version());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("nominalExposure", round(nominalEad));
        trace.put("ccf", ccf);
        trace.put("exposureAfterCcf", round(exposure));
        trace.put("exposureClass", exposureClass);
        trace.put("finalGrade", finalGrade);
        trace.put("baseRiskWeight", baseRw);
        trace.put("dueDiligenceRequired", dueDiligenceRequired);
        trace.put("dueDiligenceUplift", ddUplift);
        trace.put("appliedRiskWeight", appliedRw);
        trace.put("collateralType", in.collateralType());
        trace.put("collateralHaircut", haircut);
        trace.put("securedPortion", round(securedPortion));
        trace.put("unsecuredPortion", round(unsecuredPortion));
        trace.put("rwa", round(rwa));
        trace.put("capitalRatio", capitalRatio);
        trace.put("capitalRequired", round(capital));
        trace.put("rulePacks", Map.of(
                "capital", capPack.code() + " v" + capPack.version(),
                "ecra", ecraPack.code() + " v" + ecraPack.version()));
        r.setTrace(trace);
        return r;
    }

    // --------------------------------------------------------------- per-facility aggregation

    /** One facility's deterministic capital slice (post-CCF exposure, recognised CRM, RWA, capital). */
    public record FacilityCapital(String reference, String facilityType, double nominalAmount, double ccf,
                                  double exposure, double collateralCover, double securedPortion,
                                  double unsecuredPortion, double appliedRiskWeight, double rwa,
                                  double capitalRequired) {
    }

    /**
     * Computes each facility's capital slice deterministically. The exposure class, base risk weight,
     * due-diligence uplift and capital ratio are deal-level (segment/grade/DSCR-driven) and therefore
     * uniform across facilities; only the CCF and the recognised collateral differ per facility.
     *
     * <p>Collateral with a matching {@code facilityId} is recognised against that facility; unlinked
     * (pooled) collateral is apportioned pro-rata by post-CCF exposure. Each collateral item's
     * supervisory haircut is read from the SAME {@code crm_collateral_haircuts} pack map the single
     * -figure {@link #compute} path uses, and CRM is recognised only when the deal is {@code secured}
     * — so a single facility whose amount equals the requested amount reproduces {@link #compute}
     * exactly (byte-identical), while genuinely multi-facility deals move the authoritative RWA.
     */
    public List<FacilityCapital> perFacilityBreakdown(CreditInputsDto in, String finalGrade,
                                                      boolean dueDiligenceRequired, boolean dueDiligenceDone,
                                                      RulePackDto capPack, RulePackDto ecraPack) {
        String exposureClass = exposureClass(in.segment(), in.facilityType());
        double baseRw = baseRiskWeight(exposureClass, finalGrade, in, capPack, ecraPack);
        boolean upliftApplied = dueDiligenceRequired && !dueDiligenceDone;
        double ddUplift = upliftApplied ? capPack.number("due_diligence_uplift", 0.25) : 0.0;
        double appliedRw = baseRw + ddUplift;
        Map<String, Object> haircuts = capPack.map("crm_collateral_haircuts");

        List<FacilityInput> facilities = in.facilities();
        // Post-CCF exposure per facility.
        double[] exposures = new double[facilities.size()];
        double[] ccfs = new double[facilities.size()];
        double totalExposure = 0.0;
        for (int i = 0; i < facilities.size(); i++) {
            ccfs[i] = ccf(facilities.get(i).facilityType(), capPack);
            exposures[i] = facilities.get(i).amount() * ccfs[i];
            totalExposure += exposures[i];
        }

        // Split collateral into facility-linked vs pooled; only recognise CRM on a secured deal.
        double[] linkedCover = new double[facilities.size()];
        double pooledCover = 0.0;
        if (in.secured() && in.collaterals() != null) {
            for (CollateralInput c : in.collaterals()) {
                if (c == null || c.marketValue() <= 0 || c.collateralType() == null) {
                    continue;
                }
                double haircut = readDouble(haircuts, c.collateralType().toUpperCase(), 0.5);
                double cover = c.marketValue() * (1 - haircut);
                int idx = c.facilityId() == null ? -1 : indexOfFacility(facilities, c.facilityId());
                if (idx >= 0) {
                    linkedCover[idx] += cover;
                } else {
                    pooledCover += cover;   // unlinked pool — apportioned below
                }
            }
        }

        // Apportion the unlinked pool by each facility's RESIDUAL (still-uncovered) exposure, not its
        // gross exposure — so pooled collateral flows to facilities that actually need it and is not
        // wasted on one already fully covered by its own linked collateral (which would understate
        // total recognised cover and overstate RWA).
        double[] residual = new double[facilities.size()];
        double totalResidual = 0.0;
        for (int i = 0; i < facilities.size(); i++) {
            residual[i] = Math.max(0.0, exposures[i] - linkedCover[i]);
            totalResidual += residual[i];
        }

        double capitalRatio = capPack.number("capital_ratio_min", 0.09);
        List<FacilityCapital> out = new ArrayList<>(facilities.size());
        for (int i = 0; i < facilities.size(); i++) {
            double pooledShare = totalResidual > 0 ? pooledCover * (residual[i] / totalResidual) : 0.0;
            double cover = linkedCover[i] + pooledShare;
            double secured = Math.min(exposures[i], cover);
            double unsecured = exposures[i] - secured;
            double rwa = unsecured * appliedRw;
            out.add(new FacilityCapital(facilities.get(i).reference(), facilities.get(i).facilityType(),
                    facilities.get(i).amount(), ccfs[i], round(exposures[i]), round(cover), round(secured),
                    round(unsecured), appliedRw, round(rwa), round(rwa * capitalRatio)));
        }
        return out;
    }

    /**
     * Deterministic capital for a multi-facility deal: sums each facility's RWA (see
     * {@link #perFacilityBreakdown}) into the deal aggregate and emits the per-facility trace. Used
     * when the deal proposes 2+ facilities; the single-facility / no-facility path stays on
     * {@link #compute} so existing figures never move.
     */
    public CapitalResult computeAggregate(CreditInputsDto in, String finalGrade,
                                          boolean dueDiligenceRequired, boolean dueDiligenceDone,
                                          RulePackDto capPack, RulePackDto ecraPack) {
        String exposureClass = exposureClass(in.segment(), in.facilityType());
        double baseRw = baseRiskWeight(exposureClass, finalGrade, in, capPack, ecraPack);
        boolean upliftApplied = dueDiligenceRequired && !dueDiligenceDone;
        double ddUplift = upliftApplied ? capPack.number("due_diligence_uplift", 0.25) : 0.0;
        double appliedRw = baseRw + ddUplift;
        double capitalRatio = capPack.number("capital_ratio_min", 0.09);

        List<FacilityCapital> perFacility =
                perFacilityBreakdown(in, finalGrade, dueDiligenceRequired, dueDiligenceDone, capPack, ecraPack);

        double totalExposure = 0.0, totalSecured = 0.0, totalRwa = 0.0, totalCover = 0.0;
        List<Map<String, Object>> facilityTrace = new ArrayList<>(perFacility.size());
        for (FacilityCapital fc : perFacility) {
            totalExposure += fc.exposure();
            totalSecured += fc.securedPortion();
            totalRwa += fc.rwa();
            totalCover += fc.collateralCover();
            Map<String, Object> ft = new LinkedHashMap<>();
            ft.put("facilityReference", fc.reference());
            ft.put("facilityType", fc.facilityType());
            ft.put("nominalAmount", round(fc.nominalAmount()));
            ft.put("ccf", fc.ccf());
            ft.put("exposureAfterCcf", fc.exposure());
            ft.put("collateralCover", fc.collateralCover());
            ft.put("securedPortion", fc.securedPortion());
            ft.put("unsecuredPortion", fc.unsecuredPortion());
            ft.put("appliedRiskWeight", fc.appliedRiskWeight());
            ft.put("rwa", fc.rwa());
            ft.put("capitalRequired", fc.capitalRequired());
            facilityTrace.add(ft);
        }
        double unsecured = totalExposure - totalSecured;
        double capital = totalRwa * capitalRatio;
        // Blended CRM haircut for the summary field (effective, across recognised collateral).
        double blendedHaircut = 0.0;   // reported only; each facility used its own item haircut
        if (in.secured() && in.collaterals() != null) {
            double market = in.collaterals().stream()
                    .filter(c -> c != null && c.marketValue() > 0 && c.collateralType() != null)
                    .mapToDouble(CollateralInput::marketValue).sum();
            double coverRaw = in.collaterals().stream()
                    .filter(c -> c != null && c.marketValue() > 0 && c.collateralType() != null)
                    .mapToDouble(c -> c.marketValue()
                            * (1 - readDouble(capPack.map("crm_collateral_haircuts"), c.collateralType().toUpperCase(), 0.5)))
                    .sum();
            if (market > 0) {
                blendedHaircut = Math.max(0.0, Math.min(1.0, 1 - coverRaw / market));
            }
        }

        CapitalResult r = new CapitalResult();
        r.setApplicationReference(in.applicationReference());
        r.setJurisdiction(in.jurisdiction());
        r.setExposureClass(exposureClass);
        r.setEad(round(totalExposure));
        r.setBaseRiskWeight(baseRw);
        r.setAppliedRiskWeight(appliedRw);
        r.setDueDiligenceUpliftApplied(upliftApplied);
        r.setCollateralHaircut(blendedHaircut);
        r.setSecuredPortion(round(totalSecured));
        r.setUnsecuredPortion(round(unsecured));
        r.setRwa(round(totalRwa));
        r.setCapitalRequired(round(capital));
        r.setCapitalRatio(capitalRatio);
        r.setCapitalPackCode(capPack.code());
        r.setCapitalPackVersion(capPack.version());
        r.setEcraPackCode(ecraPack.code());
        r.setEcraPackVersion(ecraPack.version());

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("method", "PER_FACILITY_AGGREGATE");
        trace.put("facilityCount", perFacility.size());
        trace.put("exposureClass", exposureClass);
        trace.put("finalGrade", finalGrade);
        trace.put("baseRiskWeight", baseRw);
        trace.put("dueDiligenceRequired", dueDiligenceRequired);
        trace.put("dueDiligenceUplift", ddUplift);
        trace.put("appliedRiskWeight", appliedRw);
        trace.put("perFacility", facilityTrace);
        trace.put("collateralCoverRecognised", round(totalCover));
        trace.put("blendedCollateralHaircut", blendedHaircut);
        trace.put("securedPortion", round(totalSecured));
        trace.put("unsecuredPortion", round(unsecured));
        trace.put("ead", round(totalExposure));
        trace.put("rwa", round(totalRwa));
        trace.put("capitalRatio", capitalRatio);
        trace.put("capitalRequired", round(capital));
        trace.put("rulePacks", Map.of(
                "capital", capPack.code() + " v" + capPack.version(),
                "ecra", ecraPack.code() + " v" + ecraPack.version()));
        r.setTrace(trace);
        return r;
    }

    private int indexOfFacility(List<FacilityInput> facilities, Long facilityId) {
        for (int i = 0; i < facilities.size(); i++) {
            if (facilityId.equals(facilities.get(i).id())) {
                return i;
            }
        }
        return -1;   // links to a facility not in this deal -> treat as pooled
    }

    String exposureClass(String segment, String facilityType) {
        return switch (segment) {
            case "FINANCIAL_INSTITUTION" -> "BANK";
            case "SME" -> "SME_CORPORATE";
            case "PROJECT_FINANCE" -> "SPECIALISED_LENDING";
            case "LARGE_CORPORATE", "MID_CORPORATE", "TRADE_FINANCE" -> "CORPORATE";
            default -> "OTHER";
        };
    }

    private double baseRiskWeight(String exposureClass, String grade, CreditInputsDto in,
                                  RulePackDto capPack, RulePackDto ecraPack) {
        Map<String, Object> unrated = capPack.map("unrated_risk_weights");
        switch (exposureClass) {
            case "SPECIALISED_LENDING" -> {
                String slot = slottingCategory(in.ratio("DSCR"));
                return readDouble(capPack.map("slotting_risk_weights"), slot, 1.15);
            }
            case "BANK" -> {
                return ecraRiskWeight("bank", grade, ecraPack, readDouble(unrated, "BANK", 0.5));
            }
            case "CORPORATE" -> {
                double rw = ecraRiskWeight("corporate", grade, ecraPack, readDouble(unrated, "CORPORATE", 1.0));
                if (!MasterScale.isInvestmentGrade(grade)) {
                    rw = Math.max(rw, 0.0);   // sub-IG already weighted via bucket table
                }
                return rw;
            }
            case "SME_CORPORATE" -> {
                double corp = ecraRiskWeight("corporate", grade, ecraPack, readDouble(unrated, "CORPORATE", 1.0));
                double smeFactor = readDouble(unrated, "SME_CORPORATE", 0.85);
                return Math.min(smeFactor, corp);   // SME supporting factor caps the weight
            }
            default -> {
                return readDouble(unrated, exposureClass, 1.0);
            }
        }
    }

    private double ecraRiskWeight(String table, String grade, RulePackDto ecraPack, double fallback) {
        Map<String, Object> bucketMap = ecraPack.map("internal_grade_to_bucket");
        Object bucketObj = bucketMap.get(grade);
        if (bucketObj == null) {
            return fallback;
        }
        Map<String, Object> rwTable = ecraPack.map(table);
        return readDouble(rwTable, bucketObj.toString(), fallback);
    }

    private String slottingCategory(double dscr) {
        if (dscr >= 2.0) return "STRONG";
        if (dscr >= 1.5) return "GOOD";
        if (dscr >= 1.25) return "SATISFACTORY";
        return "WEAK";
    }

    private double ccf(String facilityType, RulePackDto capPack) {
        return switch (facilityType) {
            case "REVOLVING_CREDIT" -> capPack.number("ccf_undrawn_commitment", 0.4);
            case "GUARANTEE" -> capPack.number("ccf_direct_credit_substitute", 1.0);
            case "TRADE_LINE" -> capPack.number("ccf_trade_contingent", 0.2);
            default -> 1.0;   // funded term/working-capital/project exposures
        };
    }

    private double readDouble(Map<String, Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
