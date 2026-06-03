package com.helix.risk.service;

import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.CapitalResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
