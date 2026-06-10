package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.AdvisoryDtos.MacroScenarioRequest;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.entity.MacroImpactAssessment;
import com.helix.risk.entity.RagAssessment;
import com.helix.risk.entity.Rating;
import com.helix.risk.repo.MacroImpactAssessmentRepository;
import com.helix.risk.repo.RagAssessmentRepository;
import com.helix.risk.repo.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Advisory risk overlays (PRD ML/statistical RAG + macro directional impact). Both
 * are <b>non-binding</b> — they read the same ratios the deterministic scorecard reads
 * and the authoritative {@link Rating}, but never replace either. Every output is
 * audited as an AI/advisory event so the governance trail is explicit.
 */
@Service
public class AdvisoryRiskService {

    /** RAG factor weights — transparent, statistical, sum to 1.0. */
    private record Band(String key, double lo, double hi, boolean inverse, double weight) {
    }

    private static final List<Band> RAG_FACTORS = List.of(
            new Band("NET_LEVERAGE", 1.0, 6.0, true, 0.22),
            new Band("DSCR", 1.0, 2.0, false, 0.18),
            new Band("INTEREST_COVERAGE", 1.0, 6.0, false, 0.15),
            new Band("EBITDA_MARGIN", 0.02, 0.25, false, 0.12),
            new Band("CURRENT_RATIO", 0.8, 2.0, false, 0.10),
            new Band("GEARING", 0.5, 3.0, true, 0.08));
    private static final double GRADE_WEIGHT = 0.15;   // weight given to the authoritative grade

    /** Grade ladder → 0..100 strength (mirrors the scorecard's MasterScale midpoints). */
    private static final Map<String, Double> GRADE_STRENGTH = Map.ofEntries(
            Map.entry("AAA", 96.0), Map.entry("AA", 86.0), Map.entry("A", 78.0), Map.entry("BBB", 70.0),
            Map.entry("BB", 60.0), Map.entry("B", 50.0), Map.entry("CCC", 40.0), Map.entry("CC", 30.0),
            Map.entry("C", 20.0), Map.entry("D", 8.0));

    private final RatingRepository ratings;
    private final RagAssessmentRepository ragRepo;
    private final MacroImpactAssessmentRepository macroRepo;
    private final OriginationClient origination;
    private final AuditService audit;
    private final com.helix.common.governance.AiGovernanceClient governance;

    public AdvisoryRiskService(RatingRepository ratings, RagAssessmentRepository ragRepo,
                               MacroImpactAssessmentRepository macroRepo, OriginationClient origination,
                               AuditService audit, com.helix.common.governance.AiGovernanceClient governance) {
        this.ratings = ratings;
        this.ragRepo = ragRepo;
        this.macroRepo = macroRepo;
        this.origination = origination;
        this.audit = audit;
        this.governance = governance;
    }

    // --------------------------------------------------- statistical RAG

    @Transactional
    public RagAssessment assessRag(String reference, String actor) {
        CreditInputsDto in = origination.creditInputs(reference);
        governance.enforce(com.helix.common.governance.AiCapability.RAG_OVERLAY, in.jurisdiction());
        Map<String, Double> ratios = in.ratios() == null ? Map.of() : in.ratios();
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null);

        List<Map<String, Object>> breakdown = new ArrayList<>();
        double weighted = 0.0;
        for (Band f : RAG_FACTORS) {
            Double v = ratios.get(f.key());
            double sub = v == null ? 50.0 : normalise(v, f.lo(), f.hi(), f.inverse());
            double contribution = sub * f.weight();
            weighted += contribution;
            breakdown.add(factorRow(f.key(), v, sub, f.weight(), contribution, v == null));
        }
        // The authoritative grade carries its own weight in the RAG blend.
        String grade = rating == null ? null : rating.getFinalGrade();
        double gradeStrength = grade == null ? 50.0 : GRADE_STRENGTH.getOrDefault(grade, 50.0);
        double gradeContribution = gradeStrength * GRADE_WEIGHT;
        weighted += gradeContribution;
        breakdown.add(factorRow("AUTHORITATIVE_GRADE", grade == null ? null : (double) grade.length(),
                gradeStrength, GRADE_WEIGHT, gradeContribution, grade == null));

        double score = round1(weighted);
        String band = score >= 67 ? "GREEN" : score >= 45 ? "AMBER" : "RED";

        RagAssessment a = new RagAssessment();
        a.setApplicationReference(reference);
        a.setMethod("STATISTICAL_RAG");
        a.setScore(score);
        a.setBand(band);
        a.setGradeSnapshot(grade);
        a.setPdSnapshot(rating == null ? 0.0 : rating.getPd());
        a.setAdvisory(true);
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("model", "statistical_rag_v1");
        factors.put("scale", "0-100 (higher = stronger)");
        factors.put("thresholds", Map.of("green", 67, "amber", 45));
        factors.put("breakdown", breakdown);
        a.setFactors(factors);
        RagAssessment saved = ragRepo.save(a);

        audit.ai("rag-scoring", "RAG_ASSESSED", "Application", reference,
                "Statistical RAG %s (%.1f/100) — advisory, non-binding".formatted(band, score),
                Map.of("band", band, "score", score, "advisory", true, "grade", String.valueOf(grade)));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RagAssessment> ragHistory(String reference) {
        return ragRepo.findByApplicationReferenceOrderByIdDesc(reference);
    }

    // --------------------------------------------------- macro directional impact

    @Transactional
    public MacroImpactAssessment assessMacro(String reference, MacroScenarioRequest req, String actor) {
        Rating rating = ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No rating for " + reference + " — rate the deal first"));
        governance.enforce(com.helix.common.governance.AiCapability.MACRO_IMPACT,
                origination.creditInputs(reference).jurisdiction());
        double basePd = Math.max(0.0001, rating.getPd());

        double rateBps = nz(req.interestRateBps());
        double gdpDelta = nz(req.gdpGrowthDeltaPct());
        double fxDep = nz(req.fxDepreciationPct());
        double commodity = nz(req.commodityShockPct());
        String outlook = req.sectorOutlook() == null ? "STABLE" : req.sectorOutlook().toUpperCase();

        // Directional sensitivities — PD multiplier deltas (transparent, additive).
        Map<String, Object> contributions = new LinkedHashMap<>();
        double m = 0.0;
        m += addContribution(contributions, "interest_rate", rateBps / 100.0 * 0.06,
                "+100bps rates → +6% PD");
        m += addContribution(contributions, "gdp_growth", -gdpDelta * 0.05,
                "-1% GDP growth → +5% PD");
        m += addContribution(contributions, "fx_depreciation", fxDep / 10.0 * 0.04,
                "+10% FX depreciation → +4% PD");
        m += addContribution(contributions, "commodity_shock", Math.abs(commodity) / 10.0 * 0.03,
                "±10% commodity shock → +3% PD");
        double outlookDelta = switch (outlook) {
            case "DETERIORATING" -> 0.10;
            case "IMPROVING" -> -0.08;
            default -> 0.0;
        };
        m += addContribution(contributions, "sector_outlook", outlookDelta,
                "sector " + outlook.toLowerCase());

        double multiplier = Math.max(0.2, 1.0 + m);
        double stressedPd = Math.min(0.99, basePd * multiplier);
        double deltaBps = round1((stressedPd - basePd) * 10_000);
        // Notch estimate from the PD ratio on a roughly log2 scale (negative = downgrade).
        double notch = round1(-(Math.log(stressedPd / basePd) / Math.log(1.6)));
        String direction = Math.abs(deltaBps) < 1 ? "STABLE" : (deltaBps > 0 ? "UP" : "DOWN");

        String rationale = "Scenario '%s': base PD %.2f%% → stressed %.2f%% (Δ %+.0fbps), ~%.1f notch %s. %s"
                .formatted(req.scenarioName() == null ? "custom" : req.scenarioName(),
                        basePd * 100, stressedPd * 100, deltaBps, Math.abs(notch),
                        notch < -0.05 ? "downgrade pressure" : notch > 0.05 ? "upgrade headroom" : "broadly stable",
                        "Advisory — does not alter the authoritative rating.");

        MacroImpactAssessment a = new MacroImpactAssessment();
        a.setApplicationReference(reference);
        a.setScenarioName(req.scenarioName() == null ? "custom" : req.scenarioName());
        a.setBaselinePd(basePd);
        a.setStressedPd(round4(stressedPd));
        a.setPdDeltaBps(deltaBps);
        a.setDirection(direction);
        a.setNotchEstimate(notch);
        a.setRationale(rationale);
        a.setAdvisory(true);
        contributions.put("net_multiplier", round4(multiplier));
        a.setContributions(contributions);
        MacroImpactAssessment saved = macroRepo.save(a);

        audit.ai("macro-impact", "MACRO_IMPACT_ASSESSED", "Application", reference,
                "%s: PD %s %+.0fbps (~%.1f notch) — advisory".formatted(a.getScenarioName(), direction, deltaBps, notch),
                Map.of("direction", direction, "pdDeltaBps", deltaBps, "advisory", true));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<MacroImpactAssessment> macroHistory(String reference) {
        return macroRepo.findByApplicationReferenceOrderByIdDesc(reference);
    }

    // --------------------------------------------------- helpers

    private Map<String, Object> factorRow(String key, Double value, double sub, double weight,
                                          double contribution, boolean imputed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("value", value);
        row.put("subScore", round1(sub));
        row.put("weight", weight);
        row.put("contribution", round1(contribution));
        if (imputed) row.put("imputed", true);
        return row;
    }

    private double addContribution(Map<String, Object> sink, String key, double delta, String note) {
        if (Math.abs(delta) > 1e-9) {
            sink.put(key, Map.of("pdMultiplierDelta", round4(delta), "note", note));
        }
        return delta;
    }

    /** Clamp a ratio into its band and map to 0..100; inverse for "lower is better" metrics. */
    private double normalise(double v, double lo, double hi, boolean inverse) {
        double clamped = Math.max(lo, Math.min(hi, v));
        double pos = (clamped - lo) / (hi - lo);
        return 100.0 * (inverse ? 1.0 - pos : pos);
    }

    private double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
