package com.helix.risk.model;

import com.helix.risk.dto.CreditInputsDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisory recommender for STANDALONE parameters — the judgmental questions that
 * do NOT come from another CPS module/screen (management quality, business
 * profile, governance/ESG, industry outlook, …). This is the qualitative-scorecard
 * scoring capability, reinstated and scoped to non-sourced parameters: it produces
 * a 0-100 score + a rationale grounded on the deal's authoritative grade and the
 * spread-derived ratios, so every recommendation is explainable, never invented.
 *
 * <p>Deterministic LLM stand-in at the platform boundary (same posture as the
 * other AI surfaces). Advisory only — a human reviews/overrides, and the score
 * never moves the authoritative grade.</p>
 */
@Component
public class StandaloneScorer {

    public record Recommendation(double score, String rationale) { }

    /**
     * Recommend a 0-100 score for a standalone question, keyed by its {@code key}
     * (well-known judgmental dimensions) with a graceful default for any other key.
     * {@code gradeStrength} is the authoritative grade mapped to 0-100.
     */
    public Recommendation recommend(ModelDefs.Question q, CreditInputsDto in, double gradeStrength) {
        List<String> drivers = new ArrayList<>();
        String key = q.key() == null ? "" : q.key().toLowerCase();
        double s;
        if (key.contains("management") || key.contains("promoter")) {
            s = 55;
            s += apply(drivers, gradeStrength >= 67, +12, gradeStrength < 45 ? -15 : 0,
                    "authoritative grade strength %.0f/100".formatted(gradeStrength));
            s += apply(drivers, "LARGE_CORPORATE".equalsIgnoreCase(in.segment()), +8, 0, "segment " + in.segment());
            s += apply(drivers, in.secured(), +5, 0, "secured facility");
        } else if (key.contains("industry") || key.contains("sector") || key.contains("outlook")) {
            double growth = in.trends() == null ? 0 : in.trends().getOrDefault("REVENUE_GROWTH", 0.0);
            s = 50;
            s += apply(drivers, growth > 0.10, +12, growth < 0 ? -10 : 0, "revenue growth %.0f%%".formatted(growth * 100));
            s += apply(drivers, gradeStrength >= 60, +8, 0, "grade strength");
        } else if (key.contains("business") || key.contains("operating") || key.contains("profile")) {
            s = 50;
            s += apply(drivers, in.requestedAmount() >= 1_000_000_000d, +12, 0, "scale (exposure)");
            s += apply(drivers, "LARGE_CORPORATE".equalsIgnoreCase(in.segment())
                    || "MID_CORPORATE".equalsIgnoreCase(in.segment()), +8, -5, "segment " + in.segment());
        } else if (key.contains("financial") || key.contains("liquidity") || key.contains("flexibility")) {
            double cr = in.ratio("CURRENT_RATIO");
            double dscr = in.ratio("DSCR");
            double lev = in.ratio("NET_LEVERAGE");
            s = 50;
            s += apply(drivers, cr >= 1.5, +15, cr > 0 && cr < 1.0 ? -15 : 0, "current ratio %.2f".formatted(cr));
            s += apply(drivers, dscr >= 1.5, +10, dscr > 0 && dscr < 1.1 ? -12 : 0, "DSCR %.2f".formatted(dscr));
            s += apply(drivers, lev > 0 && lev < 2.0, +8, lev > 4.0 ? -12 : 0, "net leverage %.1fx".formatted(lev));
        } else if (key.contains("governance") || key.contains("esg") || key.contains("succession")) {
            s = 55;
            s += apply(drivers, gradeStrength >= 60, +8, gradeStrength < 40 ? -10 : 0, "grade strength");
            s += apply(drivers, in.secured(), +5, 0, "structure / security discipline");
        } else if (key.contains("group") || key.contains("parent") || key.contains("support")) {
            s = 50;
            s += apply(drivers, "LARGE_CORPORATE".equalsIgnoreCase(in.segment()), +10, 0, "group standing");
        } else {
            s = 50;
            s += apply(drivers, gradeStrength >= 60, +10, gradeStrength < 45 ? -10 : 0, "grade strength");
        }
        double clamped = Math.max(0, Math.min(100, s));
        String prompt = q.source() != null && q.source().prompt() != null ? q.source().prompt() : null;
        String rationale = "Recommended %.0f/100 for '%s' driven by: %s. Grounded in the deal's authoritative grade and spread-derived ratios; advisory only.%s"
                .formatted(clamped, q.label(),
                        drivers.isEmpty() ? "baseline (no strong signals)" : String.join("; ", drivers),
                        prompt == null ? "" : " [per model prompt]");
        return new Recommendation(clamped, rationale);
    }

    private double apply(List<String> drivers, boolean cond, double up, double downIfFalse, String label) {
        if (cond) { drivers.add(label + " (+" + (int) up + ")"); return up; }
        if (downIfFalse != 0) { drivers.add(label + " (" + (int) downIfFalse + ")"); return downIfFalse; }
        return 0;
    }
}
