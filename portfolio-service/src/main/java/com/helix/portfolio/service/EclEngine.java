package com.helix.portfolio.service;

import com.helix.common.model.Enums.EclStage;
import com.helix.common.model.Enums.IracClass;
import com.helix.portfolio.client.PortfolioUpstreamClient.RulePackDto;
import com.helix.portfolio.entity.EclResult;
import com.helix.portfolio.entity.ExposureRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    /** Restructure state for the RBI classification floor (null = not restructured / not applicable). */
    public record RestructureContext(boolean restructured, Instant restructuredAt) {
    }

    public EclResult compute(ExposureRecord e, RulePackDto pack) {
        return compute(e, pack, null);
    }

    public EclResult compute(ExposureRecord e, RulePackDto pack, RestructureContext restructure) {
        int dpd = e.getDaysPastDue();
        int stage2Dpd = (int) pack.number("sicr_dpd_stage2", 30);
        int stage3Dpd = (int) pack.number("sicr_dpd_stage3", 90);
        double overlay = pack.number("ecl_macro_overlay", 1.0);
        int iracSubstandardDpd = (int) pack.number("irac_dpd_substandard", 90);
        int iracDoubtfulDpd = (int) pack.number("irac_dpd_doubtful", 365);
        int notchThreshold = (int) pack.number("sicr_notch_downgrade_stage2", 3);

        int notchDowngrade = GradeLadder.downgradeNotches(e.getOriginationGrade(), e.getFinalGrade());
        boolean sicrByNotch = notchThreshold > 0 && notchDowngrade >= notchThreshold;
        EclStage stage = stage(dpd, stage2Dpd, stage3Dpd, e.getFinalGrade(), sicrByNotch);

        // ---- parallel IRAC classification ----
        Map<String, Object> iracRates = pack.map("irac_provision_rates");
        boolean iracDisabled = iracRates.isEmpty();
        IracClass iracClass = iracClass(dpd, e.getFinalGrade(), iracDisabled, iracSubstandardDpd, iracDoubtfulDpd);

        // ---- RBI restructure classification floor (IN-RBI only; hold=0 or no context => inert) ----
        int holdMonths = (int) pack.number("restructure_npa_hold_months", 0);
        boolean restructureFloorApplied = false;
        if (holdMonths > 0 && !iracDisabled && restructure != null && restructure.restructured()
                && restructure.restructuredAt() != null
                && monthsSince(restructure.restructuredAt()) < holdMonths) {
            IracClass floorClass = parseIrac(
                    String.valueOf(pack.payload().getOrDefault("restructure_classification_floor", "SUB_STANDARD")));
            if (floorClass.ordinal() > iracClass.ordinal()) {
                iracClass = floorClass;
                restructureFloorApplied = true;
            }
            if (stage.ordinal() < EclStage.STAGE_2.ordinal()) {
                stage = EclStage.STAGE_2;
                restructureFloorApplied = true;
            }
        }

        double pd12m = e.getPd();
        double pdLifetime = Math.min(1.0, 1 - Math.pow(1 - pd12m, 3));   // ~3-year horizon
        double lgd = e.getLgd();
        double ead = e.getEad();

        double ecl = switch (stage) {
            case STAGE_1 -> pd12m * lgd * ead;
            case STAGE_2 -> pdLifetime * lgd * ead;
            case STAGE_3 -> lgd * ead;   // credit-impaired, PD = 1
        } * overlay;

        // ---- SMA sub-classification of standard accounts (IN-RBI only; disabled => null) ----
        boolean smaEnabled = pack.number("sma_enabled", 0) >= 1;
        String smaClass = smaEnabled ? smaBucket(dpd, iracSubstandardDpd, pack) : null;

        // ---- IRAC provision: flat rate, or the RBI doubtful secured/unsecured age-band split ----
        double iracRate = readDouble(iracRates, iracClass.name(), 0.0);
        double iracProvision = iracRate * ead;
        String doubtfulAgeBand = null;
        double securedPortion = 0, unsecuredPortion = 0, securedProvision = 0, unsecuredProvision = 0;
        Map<String, Object> ageBands = pack.map("irac_doubtful_age_bands");
        if (iracClass == IracClass.DOUBTFUL && !ageBands.isEmpty()) {
            doubtfulAgeBand = doubtfulBand(dpd, pack);
            double securedRate = readDouble(ageBands, doubtfulAgeBand, iracRate);
            double unsecuredRate = pack.number("irac_doubtful_unsecured_rate", 1.0);
            double collateral = e.getCollateralValue() == null ? 0.0 : e.getCollateralValue();
            securedPortion = Boolean.TRUE.equals(e.getSecured()) ? Math.min(ead, collateral) : 0.0;
            unsecuredPortion = Math.max(0.0, ead - securedPortion);
            securedProvision = securedPortion * securedRate;
            unsecuredProvision = unsecuredPortion * unsecuredRate;
            iracProvision = securedProvision + unsecuredProvision;
            iracRate = ead > 0 ? iracProvision / ead : 0.0;   // effective blended rate for the field
        }

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
        r.setSmaClass(smaClass);
        r.setDoubtfulAgeBand(doubtfulAgeBand);
        r.setSecuredPortion(round2(securedPortion));
        r.setUnsecuredPortion(round2(unsecuredPortion));
        r.setSecuredProvision(round2(securedProvision));
        r.setUnsecuredProvision(round2(unsecuredProvision));
        r.setRestructureFloorApplied(restructureFloorApplied);

        Map<String, Object> trace = new TreeMap<>();
        trace.put("daysPastDue", dpd);
        trace.put("stage", stage.name());
        trace.put("originationGrade", e.getOriginationGrade());
        trace.put("currentGrade", e.getFinalGrade());
        trace.put("sicrNotchThreshold", notchThreshold);
        trace.put("sicrNotchDowngrade", notchDowngrade);
        trace.put("sicrByNotch", sicrByNotch);
        trace.put("pd12m", round(pd12m));
        trace.put("pdLifetime", round(pdLifetime));
        trace.put("lgd", lgd);
        trace.put("ead", round2(ead));
        trace.put("macroOverlay", overlay);
        trace.put("ecl", round2(ecl));
        trace.put("iracClass", iracClass.name());
        trace.put("iracDpdSubstandard", iracSubstandardDpd);
        trace.put("iracDpdDoubtful", iracDoubtfulDpd);
        trace.put("iracProvisionRate", iracRate);
        trace.put("iracProvision", round2(iracProvision));
        trace.put("smaClass", smaClass);
        trace.put("smaEnabled", smaEnabled);
        if (doubtfulAgeBand != null) {
            trace.put("doubtfulAgeBand", doubtfulAgeBand);
            trace.put("securedPortion", round2(securedPortion));
            trace.put("unsecuredPortion", round2(unsecuredPortion));
            trace.put("securedProvision", round2(securedProvision));
            trace.put("unsecuredProvision", round2(unsecuredProvision));
        }
        if (restructureFloorApplied) {
            trace.put("restructureFloorApplied", true);
            trace.put("restructuredAt", restructure == null || restructure.restructuredAt() == null
                    ? "" : restructure.restructuredAt().toString());
            trace.put("restructureHoldMonths", holdMonths);
        }
        trace.put("reportedProvisionPolicy", policy);
        trace.put("reportedProvision", round2(reported));
        trace.put("provisioningPack", pack.code() + " v" + pack.version());
        r.setTrace(trace);
        return r;
    }

    /** SMA bucket for a standard account: NONE below 1 dpd or at/above the NPA cut; SMA-0/1/2 in between. */
    private String smaBucket(int dpd, int substandardDpd, RulePackDto pack) {
        if (dpd <= 0 || dpd >= substandardDpd) return "NONE";
        if (dpd <= (int) pack.number("sma_0_max_dpd", 30)) return "SMA_0";
        if (dpd <= (int) pack.number("sma_1_max_dpd", 60)) return "SMA_1";
        if (dpd <= (int) pack.number("sma_2_max_dpd", 90)) return "SMA_2";
        return "NONE";
    }

    /** Doubtful age band from DPD past the doubtful cut: D1 (<= d1 max), D2 (<= d2 max), else D3. */
    private String doubtfulBand(int dpd, RulePackDto pack) {
        if (dpd <= (int) pack.number("irac_doubtful_d1_max_dpd", 730)) return "D1";
        if (dpd <= (int) pack.number("irac_doubtful_d2_max_dpd", 1460)) return "D2";
        return "D3";
    }

    private long monthsSince(Instant since) {
        long days = java.time.Duration.between(since, Instant.now()).toDays();
        return days / 30;   // approximate months; the hold period is a coarse supervisory window
    }

    private IracClass parseIrac(String v) {
        try {
            return IracClass.valueOf(v == null ? "SUB_STANDARD" : v.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return IracClass.SUB_STANDARD;
        }
    }

    private EclStage stage(int dpd, int stage2Dpd, int stage3Dpd, String grade, boolean sicrByNotch) {
        if (dpd >= stage3Dpd || "D".equalsIgnoreCase(grade)) {
            return EclStage.STAGE_3;
        }
        if (dpd >= stage2Dpd || SICR_GRADES.contains(grade == null ? "" : grade.toUpperCase()) || sicrByNotch) {
            return EclStage.STAGE_2;   // significant increase in credit risk (dpd, weak grade, or notch downgrade)
        }
        return EclStage.STAGE_1;
    }

    private IracClass iracClass(int dpd, String grade, boolean iracDisabled,
                                int substandardDpd, int doubtfulDpd) {
        if (iracDisabled) {
            return IracClass.STANDARD;   // jurisdiction without IRAC (e.g. CBUAE / IFRS 9 only)
        }
        if ("D".equalsIgnoreCase(grade) && dpd >= doubtfulDpd) {
            return IracClass.LOSS;
        }
        if (dpd >= doubtfulDpd) {
            return IracClass.DOUBTFUL;
        }
        if (dpd >= substandardDpd) {
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
