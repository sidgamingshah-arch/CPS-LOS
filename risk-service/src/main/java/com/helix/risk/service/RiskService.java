package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.risk.client.ConfigClient;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.RiskDtos;
import com.helix.risk.dto.RiskDtos.OverrideStats;
import com.helix.risk.dto.RiskDtos.RiskSummary;
import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.CapitalResult;
import com.helix.risk.entity.PricingResult;
import com.helix.risk.entity.Rating;
import com.helix.risk.repo.CapitalResultRepository;
import com.helix.risk.repo.PricingResultRepository;
import com.helix.risk.repo.RatingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class RiskService {

    /** Override notch limits per role (PRD §5, US-5.2). */
    private static final Map<String, Integer> NOTCH_LIMITS = Map.of(
            "ANALYST", 1, "CREDIT_OFFICER", 2, "CREDIT_COMMITTEE", 99, "CRO", 99);

    /** Model-fit alert threshold (PRD §11): override rate above this warrants challenge. */
    private static final double OVERRIDE_ALERT_THRESHOLD = 0.25;

    private final RatingEngine ratingEngine;
    private final CapitalEngine capitalEngine;
    private final PricingEngine pricingEngine;
    private final FtpService ftpService;
    private final ConfigClient config;
    private final OriginationClient origination;
    private final RatingRepository ratings;
    private final CapitalResultRepository capitalResults;
    private final PricingResultRepository pricingResults;
    private final AuditService audit;

    public RiskService(RatingEngine ratingEngine, CapitalEngine capitalEngine, PricingEngine pricingEngine,
                       FtpService ftpService, ConfigClient config, OriginationClient origination,
                       RatingRepository ratings, CapitalResultRepository capitalResults,
                       PricingResultRepository pricingResults, AuditService audit) {
        this.ratingEngine = ratingEngine;
        this.capitalEngine = capitalEngine;
        this.pricingEngine = pricingEngine;
        this.ftpService = ftpService;
        this.config = config;
        this.origination = origination;
        this.ratings = ratings;
        this.capitalResults = capitalResults;
        this.pricingResults = pricingResults;
        this.audit = audit;
    }

    // ------------------------------------------------------------------- rating

    @Transactional
    public Rating rate(String reference, String actor) {
        CreditInputsDto in = origination.creditInputs(reference);
        if (!in.spreadConfirmed()) {
            throw ApiException.conflict("Spread must be analyst-confirmed before rating (PRD §4 gate)");
        }
        RulePackDto pdPack = config.activePack(in.jurisdiction(), "RATING_PD_MAP");
        RulePackDto lgdPack = config.activePack(in.jurisdiction(), "LGD_MAP");
        RatingEngine.Computation c = ratingEngine.rate(in, pdPack, lgdPack);

        Rating rating = new Rating();
        rating.setApplicationReference(reference);
        rating.setSegment(in.segment());
        rating.setModelScore(c.score());
        rating.setModelGrade(c.grade());
        rating.setFinalGrade(c.grade());
        rating.setPd(c.pd());
        rating.setLgd(c.lgd());
        rating.setEad(c.ead());
        rating.setScoreBreakdown(c.breakdown());
        Rating saved = ratings.save(rating);

        // Deterministic scorecard output → SYSTEM actor (the figure path is never AI).
        // A human analyst proposes and a credit officer confirms via audit.human(...).
        audit.engine("RATING_PROPOSED", "Application", reference,
                "Scorecard computed grade %s (score %.1f, PD %.2f%%) — analyst proposes, approver confirms"
                        .formatted(c.grade(), c.score(), c.pd() * 100),
                Map.of("grade", c.grade(), "score", c.score(), "pd", c.pd()));
        return saved;
    }

    @Transactional
    public Rating overrideRating(String reference, RiskDtos.OverrideRatingRequest req, String actor) {
        Rating rating = latestRating(reference);
        String proposed = req.proposedGrade().toUpperCase();
        if (!MasterScale.GRADES.contains(proposed)) {
            throw ApiException.badRequest("Unknown grade: " + proposed);
        }
        if (!RiskDtos.REASON_CODES.contains(req.reasonCode())) {
            throw ApiException.badRequest("reasonCode must be one of " + RiskDtos.REASON_CODES);
        }
        String role = req.role().toUpperCase();
        int limit = NOTCH_LIMITS.getOrDefault(role, 0);

        int signedNotches = MasterScale.notches(rating.getModelGrade(), proposed); // + = upgrade
        int magnitude = Math.abs(signedNotches);
        if (magnitude == 0) {
            throw ApiException.badRequest("Proposed grade equals the model grade — nothing to override");
        }
        if (magnitude > limit) {
            throw ApiException.forbiddenAutonomy(
                    "%s may override at most %d notch(es); %d-notch %s override must be escalated to a higher authority"
                            .formatted(role, limit, magnitude, signedNotches > 0 ? "upgrade" : "downgrade"));
        }

        RulePackDto pdPack = config.activePack(jurisdictionOf(reference), "RATING_PD_MAP");
        rating.setFinalGrade(proposed);
        rating.setOverridden(true);
        rating.setOverrideNotches(signedNotches);
        rating.setReasonCode(req.reasonCode());
        rating.setOverrideNote(req.note());
        rating.setOverriddenBy(actor);
        rating.setEscalated(magnitude >= 2);
        rating.setPd(pdPack.number(proposed, rating.getPd()));
        rating.setConfirmed(false);   // override re-opens the confirmation gate
        Rating saved = ratings.save(rating);

        audit.human(actor, "RATING_OVERRIDDEN", "Application", reference,
                "Override %s -> %s (%+d notch) reason %s".formatted(
                        rating.getModelGrade(), proposed, signedNotches, req.reasonCode()),
                Map.of("model_grade", rating.getModelGrade(), "final_grade", proposed,
                        "override_notches", signedNotches, "reason_code", req.reasonCode(),
                        "approver_id", actor, "escalated", saved.isEscalated()));
        return saved;
    }

    @Transactional
    public Rating confirmRating(String reference, String actor) {
        Rating rating = latestRating(reference);
        rating.setConfirmed(true);
        rating.setConfirmedBy(actor);
        rating.setConfirmedAt(Instant.now());
        audit.human(actor, "RATING_CONFIRMED", "Application", reference,
                "Approver confirmed final grade %s".formatted(rating.getFinalGrade()),
                Map.of("final_grade", rating.getFinalGrade()));
        return ratings.save(rating);
    }

    @Transactional(readOnly = true)
    public OverrideStats overrideStats(String segment) {
        List<Rating> all = ratings.findBySegment(segment);
        long overridden = all.stream().filter(Rating::isOverridden).count();
        double rate = all.isEmpty() ? 0.0 : (double) overridden / all.size();
        return new OverrideStats(segment, all.size(), overridden,
                Math.round(rate * 1000.0) / 1000.0, rate > OVERRIDE_ALERT_THRESHOLD);
    }

    // ------------------------------------------------------------------ capital

    @Transactional
    public CapitalResult computeCapital(String reference, String actor) {
        CreditInputsDto in = origination.creditInputs(reference);
        Rating rating = latestRating(reference);
        RulePackDto capPack = config.activePack(in.jurisdiction(), "CAPITAL_SA");
        RulePackDto ecraPack = config.activePack(in.jurisdiction(), "ECRA_MAPPING");

        boolean ddRequired = "IN-RBI".equals(in.jurisdiction());
        boolean ddDone = in.spreadConfirmed();   // DD evidence captured in-platform (proxy)

        CapitalResult result = capitalEngine.compute(in, rating.getFinalGrade(), rating.getEad(),
                ddRequired, ddDone, capPack, ecraPack);
        CapitalResult saved = capitalResults.save(result);

        audit.engine("CAPITAL_COMPUTED", "Application", reference,
                "RWA %.0f, capital %.0f (%s, RW %.0f%%) per %s v%d".formatted(
                        saved.getRwa(), saved.getCapitalRequired(), saved.getExposureClass(),
                        saved.getAppliedRiskWeight() * 100, capPack.code(), capPack.version()),
                Map.of("rwa", saved.getRwa(), "capital", saved.getCapitalRequired(),
                        "exposureClass", saved.getExposureClass(), "appliedRiskWeight", saved.getAppliedRiskWeight(),
                        "capitalPack", capPack.code() + " v" + capPack.version()));
        return saved;
    }

    /** AI [D] explanation grounded in and citing the engine's own trace values (PRD §6). */
    @Transactional(readOnly = true)
    public String explainCapital(String reference) {
        CapitalResult r = latestCapital(reference);
        return ("Under %s (rule pack %s v%d), this %s exposure of %.0f attracts a base risk weight of %.0f%%%s, "
                + "giving an applied weight of %.0f%%. After CRM (haircut %.0f%%, secured portion %.0f), "
                + "RWA is %.0f and the capital requirement at %.0f%% is %.0f. "
                + "Every figure above is quoted from the deterministic engine trace; this explanation computes nothing.")
                .formatted(r.getJurisdiction(), r.getCapitalPackCode(), r.getCapitalPackVersion(),
                        r.getExposureClass(), r.getEad(), r.getBaseRiskWeight() * 100,
                        r.isDueDiligenceUpliftApplied() ? " (plus a due-diligence uplift)" : "",
                        r.getAppliedRiskWeight() * 100, r.getCollateralHaircut() * 100, r.getSecuredPortion(),
                        r.getRwa(), r.getCapitalRatio() * 100, r.getCapitalRequired());
    }

    // ------------------------------------------------------------------ pricing

    @Transactional
    public PricingResult price(String reference, String actor) {
        Rating rating = latestRating(reference);
        CapitalResult capital = latestCapital(reference);
        CreditInputsDto in = origination.creditInputs(reference);
        RulePackDto pricingPack = config.activePack(in.jurisdiction(), "PRICING");

        // Resolve the term-structured, behaviourally-adjusted FTP for this facility.
        // Falls back to the pack's flat cost_of_funds if no FTP_CURVE master exists.
        FtpService.FtpResult ftp = ftpService.computeFtp(in.currency(), in.jurisdiction(),
                in.facilityType(), in.tenorMonths(), pricingPack.number("cost_of_funds", 0.075));

        PricingResult result = pricingEngine.price(reference, rating.getPd(), rating.getLgd(),
                capital.getRwa(), rating.getEad(), pricingPack, ftp);
        PricingResult saved = pricingResults.save(result);

        // Deterministic RAROC pricing → SYSTEM actor. The AI goal-seek optimiser and
        // concession sub-workflow are separate, advisory, and stamped audit.ai elsewhere.
        audit.engine("PRICING_RECOMMENDED", "Application", reference,
                "Recommended rate %.2f%%, RAROC %.1f%% vs hurdle %.1f%%%s (deterministic)".formatted(
                        saved.getRecommendedRate() * 100, saved.getRaroc() * 100, saved.getHurdleRaroc() * 100,
                        saved.isBelowHurdle() ? " — BELOW HURDLE" : ""),
                Map.of("recommendedRate", saved.getRecommendedRate(), "raroc", saved.getRaroc(),
                        "belowHurdle", saved.isBelowHurdle()));
        return saved;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Rating latestRating(String reference) {
        return ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No rating for " + reference + " — rate the deal first"));
    }

    @Transactional(readOnly = true)
    public CapitalResult latestCapital(String reference) {
        return capitalResults.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No capital result for " + reference));
    }

    @Transactional(readOnly = true)
    public RiskSummary summary(String reference) {
        return new RiskSummary(reference,
                ratings.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null),
                capitalResults.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null),
                pricingResults.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference).orElse(null));
    }

    private String jurisdictionOf(String reference) {
        return origination.creditInputs(reference).jurisdiction();
    }
}
