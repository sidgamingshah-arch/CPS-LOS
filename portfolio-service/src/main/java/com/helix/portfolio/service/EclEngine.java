package com.helix.portfolio.service;

import com.helix.common.model.Enums.EclStage;
import com.helix.common.model.Enums.IracClass;
import com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto;
import com.helix.portfolio.entity.EclResult;
import com.helix.portfolio.entity.ExposureRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic ECL engine sharing a staging spine across IFRS 9 / Ind AS 109,
 * with a parallel IRAC provisioning view (PRD §12, US-12.2). The reported
 * provision follows the jurisdiction's policy. No AI sets a stage or a number.
 */
@Component
public class EclEngine {

    private static final List<String> SICR_GRADES = List.of("CCC", "CC", "C");

    public EclResult compute(ExposureRecord e, RulePackDto pack) {
        int dpd = e.getDaysPastDue();
        int stage2Dpd = (int) pack.number("sicr_dpd_stage2", 30);
        int stage3Dpd = (int) pack.number("sicr_dpd_stage3", 90);
        double overlay = pack.number("ecl_macro_overlay", 1.0);

        EclStage stage = stage(dpd, stage2Dpd, stage3Dpd, e.getFinalGrade());
        double pd12m = e.getPd();
        double pdLifetime = Math.min(1.0, 1 - Math.pow(1 - pd12m, 3));   // ~3-year horizon
        double lgd = e.getLgd();
        double ead = e.getEad();

        double ecl = switch (stage) {
            case STAGE_1 -> pd12m * lgd * ead;
            case STAGE_2 -> pdLifetime * lgd * ead;
            case STAGE_3 -> lgd * ead;   // credit-impaired, PD = 1
        } * overlay;

        // ---- parallel IRAC ----
        Map<String, Object> iracRates = pack.map("irac_provision_rates");
        IracClass iracClass = iracClass(dpd, e.getFinalGrade(), iracRates.isEmpty());
        double iracRate = readDouble(iracRates, iracClass.name(), 0.0);
        double iracProvision = iracRate * ead;

        String policy = String.valueOf(pack.payload().getOrDefault("reported_provision_policy", "ecl"));
        double reported = policy.contains("max") ? Math.max(ecl, iracProvision) : ecl;

        EclResult r = new EclResult();
        r.setApplicationReference(e.getApplicationReference());
        r.setStage(stage.name());
        r.setPd12m(round(pd12m));
        r.setPdLifetime(round(pdLifetime));
        r.setLgd(lgd);
        r.setEad(round2(ead));
        r.setMacroOverlay(overlay);
        r.setEcl(round2(ecl));
        r.setIracClass(iracClass.name());
        r.setIracProvisionRate(iracRate);
        r.setIracProvision(round2(iracProvision));
        r.setReportedProvision(round2(reported));
        r.setReportedProvisionPolicy(policy);
        r.setProvisioningPackCode(pack.code());
        r.setProvisioningPackVersion(pack.version());

        Map<String, Object> trace = new TreeMap<>();
        trace.put("daysPastDue", dpd);
        trace.put("stage", stage.name());
        trace.put("pd12m", round(pd12m));
        trace.put("pdLifetime", round(pdLifetime));
        trace.put("lgd", lgd);
        trace.put("ead", round2(ead));
        trace.put("macroOverlay", overlay);
        trace.put("ecl", round2(ecl));
        trace.put("iracClass", iracClass.name());
        trace.put("iracProvisionRate", iracRate);
        trace.put("iracProvision", round2(iracProvision));
        trace.put("reportedProvisionPolicy", policy);
        trace.put("reportedProvision", round2(reported));
        trace.put("provisioningPack", pack.code() + " v" + pack.version());
        r.setTrace(trace);
        return r;
    }

    private EclStage stage(int dpd, int stage2Dpd, int stage3Dpd, String grade) {
        if (dpd >= stage3Dpd || "D".equalsIgnoreCase(grade)) {
            return EclStage.STAGE_3;
        }
        if (dpd >= stage2Dpd || SICR_GRADES.contains(grade == null ? "" : grade.toUpperCase())) {
            return EclStage.STAGE_2;   // significant increase in credit risk
        }
        return EclStage.STAGE_1;
    }

    private IracClass iracClass(int dpd, String grade, boolean iracDisabled) {
        if (iracDisabled) {
            return IracClass.STANDARD;   // jurisdiction without IRAC (e.g. CBUAE / IFRS 9 only)
        }
        if ("D".equalsIgnoreCase(grade) && dpd >= 365) {
            return IracClass.LOSS;
        }
        if (dpd >= 365) {
            return IracClass.DOUBTFUL;
        }
        if (dpd >= 90) {
            return IracClass.SUB_STANDARD;
        }
        return IracClass.STANDARD;
    }

    private double readDouble(Map<String, Object> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private double round(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
