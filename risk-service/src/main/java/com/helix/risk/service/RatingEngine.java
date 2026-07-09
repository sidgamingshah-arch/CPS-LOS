package com.helix.risk.service;

import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.RulePackDto;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistical scorecard (PRD §5/§6 — governed, not generative). Produces a 0-100
 * score from weighted financial factors, maps it to the master scale, and shows
 * each factor's contribution. PD/LGD come from the active rule packs.
 */
@Component
public class RatingEngine {

    public record Factor(String key, double value, double score, double weight) {
        double contribution() {
            return score * weight;
        }
    }

    public record Computation(double score, String grade, double pd, double lgd, double ead,
                              Map<String, Object> breakdown) {
    }

    public Computation rate(CreditInputsDto in, RulePackDto pdPack, RulePackDto lgdPack) {
        List<Factor> factors = List.of(
                factor("NET_LEVERAGE", in.ratio("NET_LEVERAGE"), band(in.ratio("NET_LEVERAGE"), 1.0, 6.0, true), 0.22),
                factor("INTEREST_COVERAGE", in.ratio("INTEREST_COVERAGE"), band(in.ratio("INTEREST_COVERAGE"), 1.0, 6.0, false), 0.18),
                factor("DSCR", in.ratio("DSCR"), band(in.ratio("DSCR"), 1.0, 2.0, false), 0.18),
                factor("EBITDA_MARGIN", in.ratio("EBITDA_MARGIN"), band(in.ratio("EBITDA_MARGIN"), 0.02, 0.25, false), 0.15),
                factor("CURRENT_RATIO", in.ratio("CURRENT_RATIO"), band(in.ratio("CURRENT_RATIO"), 0.8, 2.0, false), 0.10),
                factor("GEARING", in.ratio("GEARING"), band(in.ratio("GEARING"), 0.5, 3.0, true), 0.10),
                factor("REVENUE_GROWTH", growth(in), band(growth(in), -0.10, 0.15, false), 0.07));

        double score = factors.stream().mapToDouble(Factor::contribution).sum();
        String grade = MasterScale.fromScore(score);

        double pd = pdPack.number(grade, defaultPd(grade));
        double lgd = lgd(in, lgdPack);
        double ead = ead(in);

        Map<String, Object> breakdown = new LinkedHashMap<>();
        Map<String, Object> factorMap = new LinkedHashMap<>();
        for (Factor f : factors) {
            factorMap.put(f.key(), Map.of(
                    "value", round(f.value()),
                    "score", round(f.score()),
                    "weight", f.weight(),
                    "contribution", round(f.contribution())));
        }
        breakdown.put("factors", factorMap);
        breakdown.put("totalScore", round(score));
        breakdown.put("pdSource", pdPack.code() + " v" + pdPack.version());
        breakdown.put("lgdSource", lgdPack.code() + " v" + lgdPack.version());
        breakdown.put("philosophy", "TTC");   // through-the-cycle (declared per model, PRD open Q3)

        // Quantitative provenance: every factor value is a ratio computed from the
        // financial spread (the confirmed financial template), NOT model-invented.
        // Surface the source + the underlying financials so the grade is traceable
        // straight back to the spread cells the analyst confirmed.
        Map<String, Object> quantSource = new LinkedHashMap<>();
        quantSource.put("template", "FINANCIAL_SPREAD");
        quantSource.put("spreadConfirmed", in.spreadConfirmed());
        quantSource.put("financialsUsed", in.latestFinancials() == null ? Map.of() : in.latestFinancials());
        quantSource.put("note", "Factor values are ratios derived from the confirmed financial spread; "
                + "the scorecard is deterministic and reproducible (no AI in the figure path).");
        breakdown.put("quantitativeSource", quantSource);

        return new Computation(round(score), grade, pd, lgd, ead, breakdown);
    }

    private Factor factor(String key, double value, double score, double weight) {
        return new Factor(key, value, score, weight);
    }

    /**
     * Linear band score in [0,100]. When {@code inverse} is true, lower input is better
     * (e.g. leverage); otherwise higher is better (e.g. coverage).
     */
    private double band(double value, double worst, double best, boolean inverse) {
        double lo = Math.min(worst, best);
        double hi = Math.max(worst, best);
        double clamped = Math.max(lo, Math.min(hi, value));
        double pct = (clamped - lo) / (hi - lo);
        double oriented = inverse ? (1 - pct) : pct;
        return oriented * 100.0;
    }

    private double growth(CreditInputsDto in) {
        return in.trends() == null ? 0.0 : in.trends().getOrDefault("REVENUE_GROWTH", 0.0);
    }

    private double lgd(CreditInputsDto in, RulePackDto lgdPack) {
        String key;
        if (in.secured() && in.collateralValue() >= in.requestedAmount() && in.requestedAmount() > 0) {
            key = "FULLY_COLLATERALISED";
        } else if (in.secured()) {
            key = "SENIOR_SECURED";
        } else {
            key = "SENIOR_UNSECURED";
        }
        return lgdPack.number(key, "SENIOR_UNSECURED".equals(key) ? 0.45 : 0.25);
    }

    /** Nominal exposure-at-default; capital applies CCF for off-balance items. */
    private double ead(CreditInputsDto in) {
        return in.requestedAmount();
    }

    private double defaultPd(String grade) {
        return switch (grade) {
            case "AAA" -> 0.0003;
            case "AA" -> 0.0005;
            case "A" -> 0.0010;
            case "BBB" -> 0.0030;
            case "BB" -> 0.0100;
            case "B" -> 0.0350;
            case "CCC" -> 0.1200;
            case "CC" -> 0.2500;
            case "C" -> 0.4000;
            default -> 1.0000;
        };
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
