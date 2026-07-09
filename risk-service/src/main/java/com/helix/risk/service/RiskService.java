package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.risk.client.ConfigClient;
import com.helix.risk.client.ModelDefinitionClient;
import com.helix.risk.client.ModelDefinitionClient.ResolvedModel;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.RiskDtos;
import com.helix.risk.dto.RiskDtos.OverrideStats;
import com.helix.risk.dto.RiskDtos.RiskSummary;
import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.CapitalResult;
import com.helix.risk.entity.ModelInstance;
import com.helix.risk.entity.PricingResult;
import com.helix.risk.entity.Rating;
import com.helix.risk.model.ModelDefs;
import com.helix.risk.repo.CapitalResultRepository;
import com.helix.risk.repo.ModelInstanceRepository;
import com.helix.risk.repo.PricingResultRepository;
import com.helix.risk.repo.RatingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ActorDirectory roles;
    private final AuditService audit;
    private final ModelInstanceRepository modelInstances;
    private final ModelDefinitionClient modelDefinitions;
    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    public RiskService(RatingEngine ratingEngine, CapitalEngine capitalEngine, PricingEngine pricingEngine,
                       FtpService ftpService, ConfigClient config, OriginationClient origination,
                       RatingRepository ratings, CapitalResultRepository capitalResults,
                       PricingResultRepository pricingResults, ActorDirectory roles, AuditService audit,
                       ModelInstanceRepository modelInstances, ModelDefinitionClient modelDefinitions) {
        this.ratingEngine = ratingEngine;
        this.capitalEngine = capitalEngine;
        this.pricingEngine = pricingEngine;
        this.ftpService = ftpService;
        this.config = config;
        this.origination = origination;
        this.ratings = ratings;
        this.capitalResults = capitalResults;
        this.pricingResults = pricingResults;
        this.roles = roles;
        this.audit = audit;
        this.modelInstances = modelInstances;
        this.modelDefinitions = modelDefinitions;
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

        // Default: the deterministic scorecard is authoritative (behaviour-preserving).
        double score = c.score();
        String grade = c.grade();
        double pd = c.pd();
        String gradeSource = "SCORECARD";
        Map<String, Object> breakdown = new LinkedHashMap<>(c.breakdown());

        // OPT-IN (P1 item 10): when the resolved MODEL_DEFINITION is the rating model of record AND a
        // CONFIRMED model instance exists, the model-engine composite becomes the authoritative grade —
        // mapped through the SAME MasterScale ladder the scorecard uses. Any failure -> scorecard.
        ModelInstance moi = modelOfRecordInstance(reference, in);
        if (moi != null) {
            score = moi.getCompositeScore();                 // 0-100, same scale as the scorecard score
            grade = MasterScale.fromScore(score);            // SAME score->grade ladder
            pd = pdPack.number(grade, c.pd());               // PD flows from the resulting grade via the pack
            gradeSource = "MODEL_OF_RECORD";
            breakdown.put("modelOfRecord", Map.of(
                    "modelKey", moi.getModelKey(), "modelVersion", moi.getModelVersion(),
                    "compositeScore", score,
                    "compositeBand", moi.getCompositeBand() == null ? "" : moi.getCompositeBand(),
                    "instanceId", moi.getId(),
                    "confirmedBy", moi.getConfirmedBy() == null ? "" : moi.getConfirmedBy()));
        }
        breakdown.put("gradeSource", gradeSource);           // provenance: SCORECARD | MODEL_OF_RECORD

        Rating rating = new Rating();
        rating.setApplicationReference(reference);
        rating.setSegment(in.segment());
        rating.setModelScore(score);
        rating.setModelGrade(grade);
        rating.setFinalGrade(grade);
        rating.setPd(pd);
        rating.setLgd(c.lgd());                              // LGD/EAD are grade-independent — unchanged
        rating.setEad(c.ead());
        rating.setScoreBreakdown(breakdown);
        Rating saved = ratings.save(rating);

        // Deterministic figure path → SYSTEM actor; a human analyst proposes and an approver confirms.
        audit.engine("RATING_PROPOSED", "Application", reference,
                "%s computed grade %s (score %.1f, PD %.2f%%) — analyst proposes, approver confirms"
                        .formatted(gradeSource.equals("MODEL_OF_RECORD") ? "Rating model of record" : "Scorecard",
                                grade, score, pd * 100),
                Map.of("grade", grade, "score", score, "pd", pd, "gradeSource", gradeSource));
        return saved;
    }

    /**
     * The confirmed model instance IFF the deal's resolved MODEL_DEFINITION is the rating model of
     * record and a CONFIRMED instance with a computed composite exists; else null (scorecard fallback).
     * Resilient: any resolve/parse/repo failure -> null (never blocks the deterministic figure path).
     */
    private ModelInstance modelOfRecordInstance(String reference, CreditInputsDto in) {
        try {
            ResolvedModel rm = modelDefinitions.resolve(in.jurisdiction(), in.sector(), in.segment());
            if (rm == null || !ModelDefs.parse(rm.payload()).ratingModelOfRecord()) return null;
            ModelInstance mi = modelInstances.findByApplicationReference(reference).orElse(null);
            if (mi == null || !"CONFIRMED".equals(mi.getStatus()) || mi.getCompositeScore() <= 0) return null;
            return mi;
        } catch (Exception e) {
            log.warn("model-of-record resolution failed for {} ({}); scorecard fallback", reference, e.getMessage());
            return null;
        }
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
        // Notch authority is resolved from the ACTOR_ROLE master (G1), never req.role();
        // the request-body role remains only an advisory claim, not a grant.
        int limit = resolveNotchLimit(actor);

        int signedNotches = MasterScale.notches(rating.getModelGrade(), proposed); // + = upgrade
        int magnitude = Math.abs(signedNotches);
        if (magnitude == 0) {
            throw ApiException.badRequest("Proposed grade equals the model grade — nothing to override");
        }
        if (magnitude > limit) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '%s' may override at most %d notch(es); %d-notch %s override must be escalated to a higher authority"
                            .formatted(actor, limit, magnitude, signedNotches > 0 ? "upgrade" : "downgrade"));
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
        // G1: confirm is authoritative (it unlocks DoA routing) — require CREDIT_OFFICER
        // authority or higher, resolved from the ACTOR_ROLE master (never a request-body
        // role). Phase C adds maker != checker (the confirmer must differ from the analyst
        // who proposed / overrode the rating).
        if (resolveNotchLimit(actor) < NOTCH_LIMITS.get("CREDIT_OFFICER")) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' is not authorised to confirm a rating — needs CREDIT_OFFICER authority or higher");
        }
        // Phase C — maker != checker: a human maker exists only when a grade was overridden.
        // The overrider cannot rubber-stamp their own override; a pure engine rating (no human
        // overrider) may be confirmed by any authorised approver.
        if (rating.getOverriddenBy() != null && actor.equalsIgnoreCase(rating.getOverriddenBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Confirmer '" + actor + "' cannot confirm a rating they themselves overrode — maker must differ from checker");
        }
        rating.setConfirmed(true);
        rating.setConfirmedBy(actor);
        rating.setConfirmedAt(Instant.now());
        audit.human(actor, "RATING_CONFIRMED", "Application", reference,
                "Approver confirmed final grade %s".formatted(rating.getFinalGrade()),
                Map.of("final_grade", rating.getFinalGrade()));
        return ratings.save(rating);
    }

    /**
     * The actor's maximum override-notch authority, resolved from the roles the ACTOR_ROLE
     * master grants them (G1) — never a request-body role. A cold-start directory outage
     * fails open (99), consistent with ActorDirectory parity.
     */
    private int resolveNotchLimit(String actor) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return 99;   // directory outage — fail open
        return actorRoles.stream()
                .mapToInt(r -> NOTCH_LIMITS.getOrDefault(r.toUpperCase(), 0))
                .max().orElse(0);
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
