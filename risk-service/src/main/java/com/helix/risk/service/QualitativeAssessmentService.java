package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.client.RiskMasterClient;
import com.helix.risk.client.RiskMasterClient.MasterRecordDto;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.QualitativeDtos.QualLine;
import com.helix.risk.dto.QualitativeDtos.QualitativeView;
import com.helix.risk.entity.QualitativeAssessment;
import com.helix.risk.entity.Rating;
import com.helix.risk.repo.QualitativeAssessmentRepository;
import com.helix.risk.repo.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Qualitative scorecard — the advisory counterpart to the deterministic financial
 * scorecard. For each qualitative parameter configured in the {@code QUAL_SCORECARD}
 * master (the front-end / model-document prompt library), it runs the parameter's
 * prompt against the deal's data to RECOMMEND a 0-100 score + rationale, rolls them
 * up to a weighted composite + band, and surfaces an advisory notch suggestion.
 *
 * <p>Strictly governed: every score is {@code advisory = true}, stamped
 * {@code audit.ai("qualitative-scorecard", ...)}, and a named human confirms (and may
 * adjust) each parameter. <b>The authoritative financial grade is never mutated here</b>
 * — qualitative judgment reaches the grade only via the existing notch-limited,
 * SoD-gated human override, for which this provides the recommendation.</p>
 *
 * <p>The recommender is a deterministic heuristic standing in for the LLM at the
 * platform boundary (same pattern as the other AI surfaces); it is grounded on the
 * authoritative grade and the spread-derived ratios so the scores and rationales trace
 * to real deal data, not invention.</p>
 */
@Service
public class QualitativeAssessmentService {

    private static final List<String> GRADE_LADDER =
            List.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC", "CC", "C", "D");

    private final QualitativeAssessmentRepository repo;
    private final RiskMasterClient masters;
    private final OriginationClient origination;
    private final RatingRepository ratings;
    private final com.helix.common.governance.AiGovernanceClient governance;
    private final AuditService audit;

    public QualitativeAssessmentService(QualitativeAssessmentRepository repo, RiskMasterClient masters,
                                        OriginationClient origination, RatingRepository ratings,
                                        com.helix.common.governance.AiGovernanceClient governance,
                                        AuditService audit) {
        this.repo = repo;
        this.masters = masters;
        this.origination = origination;
        this.ratings = ratings;
        this.governance = governance;
        this.audit = audit;
    }

    // ============================================================ assess (AI-advisory)

    @Transactional
    public QualitativeView assess(String reference, String actor) {
        CreditInputsDto in = origination.creditInputs(reference);
        if (in == null) throw ApiException.notFound("No credit inputs for " + reference);
        // Governed AI: the qualitative engine is subject to the off-switch like every
        // other AI capability (jurisdiction-overridable). Disabled => 403.
        governance.enforce(com.helix.common.governance.AiCapability.QUALITATIVE_SCORECARD, in.jurisdiction());
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);
        String grade = rating == null ? null : rating.getFinalGrade();
        double gradeStrength = gradeStrength(grade);

        List<MasterRecordDto> params = resolveParams(in.jurisdiction());
        if (params.isEmpty()) {
            throw ApiException.conflict("No QUAL_SCORECARD parameters configured — seed or extract from a model document first");
        }

        // Re-run replaces prior SUGGESTED rows (keep CONFIRMED history intact would
        // require a versioning model; for the demo we clear and re-suggest, but never
        // touch the authoritative grade).
        List<QualitativeAssessment> prior = repo.findByApplicationReferenceOrderByIdAsc(reference);
        repo.deleteAll(prior);

        List<QualitativeAssessment> saved = new ArrayList<>();
        for (MasterRecordDto p : params) {
            Map<String, Object> payload = p.payload() == null ? Map.of() : p.payload();
            String displayName = str(payload.get("displayName"), p.recordKey());
            double weight = num(payload.get("weight"), 0.0);
            String prompt = str(payload.get("prompt"), "");
            String promptSource = str(payload.get("source"), "SEED");

            Scored s = recommend(p.recordKey(), in, gradeStrength);
            QualitativeAssessment qa = new QualitativeAssessment();
            qa.setApplicationReference(reference);
            qa.setParameterKey(p.recordKey());
            qa.setDisplayName(displayName);
            qa.setWeight(weight);
            qa.setSuggestedScore(round1(s.score()));
            qa.setScore(round1(s.score()));
            qa.setBand(bandFor(s.score()));
            qa.setRationale(s.rationale());
            qa.setPrompt(prompt);
            qa.setPromptSource(promptSource);
            qa.setStatus("SUGGESTED");
            saved.add(repo.save(qa));
        }

        QualitativeView view = view(reference, grade);
        audit.ai("qualitative-scorecard", "QUAL_ASSESSED", "Application", reference,
                "Qualitative scorecard %s (%.1f/100, %d params) — advisory; authoritative grade %s unchanged"
                        .formatted(view.compositeBand(), view.compositeScore(), saved.size(),
                                grade == null ? "n/a" : grade),
                Map.of("compositeScore", view.compositeScore(), "band", view.compositeBand(),
                        "advisory", true, "grade", grade == null ? "" : grade,
                        "suggestedNotch", view.suggestedNotch()));
        return view;
    }

    // ============================================================ confirm (human gate)

    @Transactional
    public QualitativeView confirm(Long id, Double scoreOverride, String note, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to confirm a qualitative score");
        }
        QualitativeAssessment qa = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("No qualitative assessment: " + id));
        if (scoreOverride != null) {
            if (scoreOverride < 0 || scoreOverride > 100) {
                throw ApiException.badRequest("score must be in [0,100]");
            }
            qa.setScore(round1(scoreOverride));
            qa.setBand(bandFor(scoreOverride));
        }
        qa.setStatus("CONFIRMED");
        qa.setConfirmedBy(actor);
        qa.setConfirmedAt(Instant.now());
        repo.save(qa);
        audit.human(actor, "QUAL_CONFIRMED", "QualitativeAssessment", String.valueOf(id),
                "Confirmed '%s' qualitative score %.1f%s".formatted(qa.getDisplayName(), qa.getScore(),
                        scoreOverride != null ? " (adjusted from AI suggestion)" : ""),
                Map.of("parameterKey", qa.getParameterKey(), "score", qa.getScore(),
                        "adjusted", scoreOverride != null));
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(qa.getApplicationReference())
                .orElse(null);
        return view(qa.getApplicationReference(), rating == null ? null : rating.getFinalGrade());
    }

    @Transactional(readOnly = true)
    public QualitativeView get(String reference) {
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);
        return view(reference, rating == null ? null : rating.getFinalGrade());
    }

    // ============================================================ view assembly

    private QualitativeView view(String reference, String grade) {
        List<QualitativeAssessment> rows = repo.findByApplicationReferenceOrderByIdAsc(reference);
        List<QualLine> lines = new ArrayList<>();
        double weighted = 0, weightSum = 0;
        boolean allConfirmed = !rows.isEmpty();
        for (QualitativeAssessment qa : rows) {
            weighted += qa.getScore() * qa.getWeight();
            weightSum += qa.getWeight();
            if (!"CONFIRMED".equals(qa.getStatus())) allConfirmed = false;
            lines.add(new QualLine(qa.getId(), qa.getParameterKey(), qa.getDisplayName(), qa.getWeight(),
                    qa.getSuggestedScore(), qa.getScore(), qa.getBand(), qa.getRationale(),
                    qa.getPrompt(), qa.getPromptSource(), qa.getStatus(), qa.getConfirmedBy()));
        }
        double composite = weightSum > 0 ? weighted / weightSum : 0;
        String band = bandFor(composite);
        // Advisory notch suggestion only — never applied to the grade automatically.
        int suggestedNotch = composite >= 67 ? 1 : composite < 45 ? -1 : 0;
        return new QualitativeView(reference, round1(composite), band, suggestedNotch,
                allConfirmed, rows.size(), lines, grade, true);
    }

    // ============================================================ master resolution

    /** Active QUAL_SCORECARD params, jurisdiction-resolved (override wins over default). */
    private List<MasterRecordDto> resolveParams(String jurisdiction) {
        List<MasterRecordDto> all = masters.listActive("QUAL_SCORECARD");
        Map<String, MasterRecordDto> chosen = new java.util.LinkedHashMap<>();
        for (MasterRecordDto m : all) {
            String j = m.jurisdiction();
            boolean isOverride = jurisdiction != null && jurisdiction.equals(j);
            boolean isDefault = j == null || j.isBlank();
            if (!isOverride && !isDefault) continue;
            MasterRecordDto existing = chosen.get(m.recordKey());
            // Override beats default; otherwise keep the first.
            if (existing == null || isOverride) chosen.put(m.recordKey(), m);
        }
        return new ArrayList<>(chosen.values());
    }

    // ============================================================ the recommender (LLM stand-in)

    private record Scored(double score, String rationale) { }

    /**
     * Deterministic, grounded recommender. Each parameter maps to the deal signals a
     * rating analyst would weigh for it; the rationale enumerates the drivers so the
     * score is explainable and traceable (not invented).
     */
    private Scored recommend(String key, CreditInputsDto in, double gradeStrength) {
        List<String> drivers = new ArrayList<>();
        double s;
        switch (key) {
            case "management_quality" -> {
                s = 55;
                s += apply(drivers, gradeStrength >= 67, +12, gradeStrength < 45 ? -15 : 0,
                        "authoritative grade strength %.0f/100".formatted(gradeStrength));
                s += apply(drivers, "LARGE_CORPORATE".equalsIgnoreCase(in.segment()), +8, 0,
                        "segment " + in.segment());
                s += apply(drivers, in.secured(), +5, 0, "secured facility");
            }
            case "industry_outlook" -> {
                double growth = in.trends() == null ? 0 : in.trends().getOrDefault("REVENUE_GROWTH", 0.0);
                s = 50;
                s += apply(drivers, growth > 0.10, +12, growth < 0 ? -10 : 0,
                        "revenue growth %.0f%%".formatted(growth * 100));
                s += apply(drivers, gradeStrength >= 60, +8, 0, "grade strength");
            }
            case "business_profile" -> {
                s = 50;
                s += apply(drivers, in.requestedAmount() >= 1_000_000_000d, +12, 0, "scale (exposure)");
                s += apply(drivers, "LARGE_CORPORATE".equalsIgnoreCase(in.segment())
                        || "MID_CORPORATE".equalsIgnoreCase(in.segment()), +8, -5, "segment " + in.segment());
            }
            case "financial_flexibility" -> {
                // Grounded directly in the spread-derived liquidity / coverage ratios.
                double cr = in.ratio("CURRENT_RATIO");
                double dscr = in.ratio("DSCR");
                double lev = in.ratio("NET_LEVERAGE");
                s = 50;
                s += apply(drivers, cr >= 1.5, +15, cr < 1.0 ? -15 : 0, "current ratio %.2f".formatted(cr));
                s += apply(drivers, dscr >= 1.5, +10, dscr < 1.1 ? -12 : 0, "DSCR %.2f".formatted(dscr));
                s += apply(drivers, lev > 0 && lev < 2.0, +8, lev > 4.0 ? -12 : 0,
                        "net leverage %.1fx".formatted(lev));
            }
            case "governance_esg" -> {
                s = 55;
                s += apply(drivers, gradeStrength >= 60, +8, gradeStrength < 40 ? -10 : 0, "grade strength");
                s += apply(drivers, in.secured(), +5, 0, "security/structure discipline");
            }
            case "group_support" -> {
                s = 50;
                s += apply(drivers, "LARGE_CORPORATE".equalsIgnoreCase(in.segment()), +10, 0, "group standing");
            }
            default -> {
                s = 50;
                s += apply(drivers, gradeStrength >= 60, +10, gradeStrength < 45 ? -10 : 0, "grade strength");
            }
        }
        double clamped = Math.max(0, Math.min(100, s));
        String rationale = "Score %.0f/100 driven by: %s. Grounded in the deal's authoritative grade and "
                .formatted(clamped, drivers.isEmpty() ? "baseline (no strong signals either way)" : String.join("; ", drivers))
                + "spread-derived ratios; advisory only.";
        return new Scored(clamped, rationale);
    }

    private double apply(List<String> drivers, boolean cond, double up, double downIfFalse, String label) {
        if (cond) {
            drivers.add(label + " (+" + (int) up + ")");
            return up;
        }
        if (downIfFalse != 0) {
            drivers.add(label + " (" + (int) downIfFalse + ")");
            return downIfFalse;
        }
        return 0;
    }

    private double gradeStrength(String grade) {
        if (grade == null) return 50;
        int i = GRADE_LADDER.indexOf(grade.toUpperCase());
        if (i < 0) return 50;
        return Math.round((1.0 - (double) i / (GRADE_LADDER.size() - 1)) * 100);
    }

    private static String bandFor(double score) {
        return score >= 67 ? "STRONG" : score >= 45 ? "ADEQUATE" : "WEAK";
    }

    private static double num(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignored) { }
        return dflt;
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
