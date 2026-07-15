package com.helix.risk.service;

import com.helix.risk.client.DefaultRulePacks;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.RulePackDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistical scorecard (PRD §5/§6 — governed, not generative). Produces a 0-100
 * score from weighted financial factors, maps it to the master scale, and shows
 * each factor's contribution. PD/LGD come from the active rule packs.
 *
 * <p>Factor keys/weights/bands and the score→grade cut-points come from the versioned,
 * dual-signed SCORECARD rule pack (config-service). If the pack is unavailable or
 * malformed, the engine degrades to the built-in constants it originally shipped with
 * ({@link DefaultRulePacks}) — the seeded pack is a verbatim copy of those constants,
 * so the move from code to config never moves a grade.
 */
@Component
public class RatingEngine {

    private static final Logger log = LoggerFactory.getLogger(RatingEngine.class);

    /** Marker stamped into the breakdown when the built-in constants (not a pack) scored the deal. */
    static final String BUILT_IN_SOURCE = "BUILT_IN";

    public record Factor(String key, double value, double score, double weight) {
        double contribution() {
            return score * weight;
        }
    }

    public record Computation(double score, String grade, double pd, double lgd, double ead,
                              Map<String, Object> breakdown) {
    }

    /** One scorecard factor definition from the SCORECARD pack payload. */
    private record FactorDef(String key, double weight, double worst, double best,
                             boolean inverse, String source) {
    }

    private record GradeCutoff(double minScore, String grade) {
    }

    /** Parsed scorecard: factor definitions + descending grade cut-points + provenance label. */
    private record Scorecard(List<FactorDef> factors, List<GradeCutoff> cutoffs, String source) {
    }

    public Computation rate(CreditInputsDto in, RulePackDto pdPack, RulePackDto lgdPack,
                            RulePackDto scorecardPack) {
        Scorecard card = resolveScorecard(scorecardPack, in.jurisdiction());

        List<Factor> factors = card.factors().stream()
                .map(d -> {
                    double value = factorValue(in, d);
                    return new Factor(d.key(), value, band(value, d.worst(), d.best(), d.inverse()), d.weight());
                })
                .toList();

        double score = factors.stream().mapToDouble(Factor::contribution).sum();
        String grade = gradeFor(score, card.cutoffs());

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
        breakdown.put("scorecardSource", card.source());   // provenance: "<pack> vN" | BUILT_IN
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

    // -------------------------------------------------------- scorecard resolution

    /**
     * Parse the SCORECARD pack defensively; any structural problem (missing/empty lists,
     * non-numeric weights, unknown shapes) rejects the WHOLE pack and falls back to the
     * built-in constants — a half-applied scorecard must never score a deal. Version 0
     * marks the ConfigClient's own outage fallback, which is also stamped BUILT_IN.
     */
    private Scorecard resolveScorecard(RulePackDto pack, String jurisdiction) {
        if (pack != null && pack.version() > 0) {
            Scorecard parsed = parseScorecard(pack, pack.code() + " v" + pack.version());
            if (parsed != null) {
                return parsed;
            }
            log.warn("SCORECARD pack {} v{} for {} is malformed; using built-in scorecard constants",
                    pack.code(), pack.version(), jurisdiction);
        }
        Scorecard builtIn = parseScorecard(DefaultRulePacks.fallback(jurisdiction, "SCORECARD"), BUILT_IN_SOURCE);
        if (builtIn == null) {
            throw new IllegalStateException("Built-in SCORECARD fallback failed to parse — engine defect");
        }
        return builtIn;
    }

    private Scorecard parseScorecard(RulePackDto pack, String source) {
        try {
            Map<String, Object> payload = pack.payload();
            if (payload == null
                    || !(payload.get("factors") instanceof List<?> rawFactors) || rawFactors.isEmpty()
                    || !(payload.get("gradeCutoffs") instanceof List<?> rawCutoffs) || rawCutoffs.isEmpty()) {
                return null;
            }
            List<FactorDef> factors = new ArrayList<>();
            for (Object o : rawFactors) {
                if (!(o instanceof Map<?, ?> m)) {
                    return null;
                }
                String key = asString(m.get("key"));
                Double weight = asDouble(m.get("weight"));
                Double worst = asDouble(m.get("worst"));
                Double best = asDouble(m.get("best"));
                if (key == null || weight == null || worst == null || best == null
                        || worst.doubleValue() == best.doubleValue()) {
                    return null;
                }
                boolean inverse = m.get("inverse") instanceof Boolean b && b;
                String src = asString(m.get("source"));
                factors.add(new FactorDef(key, weight, worst, best, inverse, src == null ? "RATIO" : src));
            }
            List<GradeCutoff> cutoffs = new ArrayList<>();
            for (Object o : rawCutoffs) {
                if (!(o instanceof Map<?, ?> m)) {
                    return null;
                }
                Double minScore = asDouble(m.get("minScore"));
                String grade = asString(m.get("grade"));
                if (minScore == null || grade == null) {
                    return null;
                }
                cutoffs.add(new GradeCutoff(minScore, grade));
            }
            cutoffs.sort(Comparator.comparingDouble(GradeCutoff::minScore).reversed());
            return new Scorecard(List.copyOf(factors), List.copyOf(cutoffs), source);
        } catch (Exception e) {
            log.warn("SCORECARD payload parse failed ({}); using built-in scorecard constants", e.getMessage());
            return null;
        }
    }

    /** RATIO factors read the spread's ratio map; TREND factors read the trend map (e.g. REVENUE_GROWTH). */
    private double factorValue(CreditInputsDto in, FactorDef def) {
        if ("TREND".equalsIgnoreCase(def.source())) {
            return in.trends() == null ? 0.0 : in.trends().getOrDefault(def.key(), 0.0);
        }
        return in.ratio(def.key());
    }

    /** Highest cut-point at or below the score wins; scores below every cut-point take the worst grade. */
    private String gradeFor(double score, List<GradeCutoff> cutoffs) {
        for (GradeCutoff c : cutoffs) {
            if (score >= c.minScore()) {
                return c.grade();
            }
        }
        return cutoffs.get(cutoffs.size() - 1).grade();
    }

    private static String asString(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
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
