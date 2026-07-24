package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.risk.client.ConfigClient;
import com.helix.risk.client.ModelDefinitionClient;
import com.helix.risk.client.ModelDefinitionClient.ResolvedModel;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.client.RiskMasterClient;
import com.helix.risk.client.ScoringApprovalPolicyClient;
import com.helix.risk.client.ScoringApprovalPolicyClient.Params;
import com.helix.risk.client.ScoringApprovalPolicyClient.Resolution;
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

    /**
     * Conservative built-in FALLBACK for the per-role override-notch limits (PRD §5, US-5.2).
     * The authoritative limits are now admin-configurable in the {@code OVERRIDE_ROLE} CODE_VALUE
     * master (config-service; the notch is the per-role {@code score}). These constants are used
     * ONLY when config-service is unreachable / the domain is unscored, and are kept byte-identical
     * to the seeded master values so notch behaviour is preserved during a config outage.
     */
    private static final Map<String, Integer> NOTCH_LIMITS = Map.of(
            "ANALYST", 1, "CREDIT_OFFICER", 2, "CREDIT_COMMITTEE", 99, "CRO", 99);

    /** The CODE_VALUE domain whose per-role {@code score} is the admin-configurable notch limit. */
    private static final String OVERRIDE_ROLE_DOMAIN = "OVERRIDE_ROLE";

    /**
     * Rating-confirm authority ladder (ANALYST &lt; CREDIT_OFFICER &lt; CREDIT_COMMITTEE / CRO).
     * Maps both the required-authority string resolved from the SCORING_APPROVAL_POLICY and the
     * roles an actor holds (ACTOR_ROLE master) onto one rank. CREDIT_OFFICER is the behaviour-
     * preserving baseline: a confirmer must always hold at least CREDIT_OFFICER, exactly the
     * pre-existing flat gate. Higher-tier scores route to CREDIT_COMMITTEE / CRO.
     */
    private static final Map<String, Integer> AUTHORITY_RANK = Map.of(
            "ANALYST", 1, "CREDIT_OFFICER", 2, "CREDIT_COMMITTEE", 3, "CRO", 3, "BOARD_COMMITTEE", 4);

    /** The baseline confirm authority — the legacy mandatory gate every rating still clears. */
    private static final int BASELINE_AUTHORITY_RANK = AUTHORITY_RANK.get("CREDIT_OFFICER");

    /** Model-fit alert threshold (PRD §11): override rate above this warrants challenge. */
    private static final double OVERRIDE_ALERT_THRESHOLD = 0.25;

    private final RatingEngine ratingEngine;
    private final CapitalEngine capitalEngine;
    private final PricingEngine pricingEngine;
    private final FtpService ftpService;
    private final ConfigClient config;
    private final RiskMasterClient masters;
    private final OriginationClient origination;
    private final RatingRepository ratings;
    private final CapitalResultRepository capitalResults;
    private final PricingResultRepository pricingResults;
    private final ActorDirectory roles;
    private final AuditService audit;
    private final ModelInstanceRepository modelInstances;
    private final ModelDefinitionClient modelDefinitions;

    /**
     * Configurable, parameter-routed scoring-approval policy engine. Optional (fails open to the
     * flat CREDIT_OFFICER gate when the bean/property is absent) so the deterministic figure path
     * never depends on it.
     */
    private final ScoringApprovalPolicyClient scoringApprovalPolicy;

    /**
     * Optional best-effort case-management mirror; bean absent when the workflow-service URL isn't
     * configured. The RATING_APPROVAL work-item mirrors the authoritative approval routing — a
     * mirror failure is swallowed by the client and never reaches this transaction (never blocks
     * a rating on a workflow outage).
     */
    private final com.helix.common.workflow.TaskClient taskClient;

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    public RiskService(RatingEngine ratingEngine, CapitalEngine capitalEngine, PricingEngine pricingEngine,
                       FtpService ftpService, ConfigClient config, RiskMasterClient masters,
                       OriginationClient origination,
                       RatingRepository ratings, CapitalResultRepository capitalResults,
                       PricingResultRepository pricingResults, ActorDirectory roles, AuditService audit,
                       ModelInstanceRepository modelInstances, ModelDefinitionClient modelDefinitions,
                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                       ScoringApprovalPolicyClient scoringApprovalPolicy,
                       @org.springframework.beans.factory.annotation.Autowired(required = false)
                       com.helix.common.workflow.TaskClient taskClient) {
        this.ratingEngine = ratingEngine;
        this.capitalEngine = capitalEngine;
        this.pricingEngine = pricingEngine;
        this.ftpService = ftpService;
        this.config = config;
        this.masters = masters;
        this.origination = origination;
        this.ratings = ratings;
        this.capitalResults = capitalResults;
        this.pricingResults = pricingResults;
        this.roles = roles;
        this.audit = audit;
        this.modelInstances = modelInstances;
        this.modelDefinitions = modelDefinitions;
        this.scoringApprovalPolicy = scoringApprovalPolicy;
        this.taskClient = taskClient;
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
        // Scorecard weights/bands/cut-points are pack-driven (versioned, dual-signed);
        // ConfigClient + RatingEngine degrade to the identical built-in constants.
        RulePackDto scorecardPack = config.activePack(in.jurisdiction(), "SCORECARD");
        RatingEngine.Computation c = ratingEngine.rate(in, pdPack, lgdPack, scorecardPack);

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
        // Route the scoring (score) approval per the configurable SCORING_APPROVAL_POLICY. This is a
        // GATE, never a figure change: the grade/PD/LGD/EAD above are untouched by the routing.
        applyScoringApproval(rating, new Params(rating.getEad(), grade, scoreBand(score),
                0, false, in.segment(), in.jurisdiction()));
        Rating saved = ratings.save(rating);

        // Deterministic figure path → SYSTEM actor; a human analyst proposes and an approver confirms.
        audit.engine("RATING_PROPOSED", "Application", reference,
                "%s computed grade %s (score %.1f, PD %.2f%%) — analyst proposes, approver confirms"
                        .formatted(gradeSource.equals("MODEL_OF_RECORD") ? "Rating model of record" : "Scorecard",
                                grade, score, pd * 100),
                Map.of("grade", grade, "score", score, "pd", pd, "gradeSource", gradeSource));
        raiseRatingApprovalTask(saved, "Score proposed: grade " + grade, actor);
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

        String jurisdiction = jurisdictionOf(reference);
        RulePackDto pdPack = config.activePack(jurisdiction, "RATING_PD_MAP");
        rating.setFinalGrade(proposed);
        rating.setOverridden(true);
        rating.setOverrideNotches(signedNotches);
        rating.setReasonCode(req.reasonCode());
        rating.setOverrideNote(req.note());
        rating.setOverriddenBy(actor);
        rating.setEscalated(magnitude >= 2);
        rating.setPd(pdPack.number(proposed, rating.getPd()));
        rating.setConfirmed(false);   // override re-opens the confirmation gate
        // Re-route the scoring approval against the OVERRIDDEN params (final grade + override
        // magnitude). Still a gate, never a figure change — finalGrade/PD above are authoritative.
        // Jurisdiction via jurisdictionOf(...); segment is the rating's own persisted value.
        applyScoringApproval(rating, new Params(rating.getEad(), proposed, scoreBand(rating.getModelScore()),
                signedNotches, true, rating.getSegment(), jurisdiction));
        Rating saved = ratings.save(rating);

        audit.human(actor, "RATING_OVERRIDDEN", "Application", reference,
                "Override %s -> %s (%+d notch) reason %s".formatted(
                        rating.getModelGrade(), proposed, signedNotches, req.reasonCode()),
                Map.of("model_grade", rating.getModelGrade(), "final_grade", proposed,
                        "override_notches", signedNotches, "reason_code", req.reasonCode(),
                        "approver_id", actor, "escalated", saved.isEscalated()));
        raiseRatingApprovalTask(saved, "Override to " + proposed + " (" + signedNotches + " notch)", actor);
        return saved;
    }

    @Transactional
    public Rating confirmRating(String reference, String actor) {
        Rating rating = latestRating(reference);
        // G1: confirm is authoritative (it unlocks DoA routing). The required authority is now
        // resolved from the configurable SCORING_APPROVAL_POLICY (persisted on the rating when it
        // was scored/overridden), floored at CREDIT_OFFICER — so ordinary scores keep the exact
        // pre-existing flat gate, while a large-exposure / deep-override / sub-investment score
        // routes to CREDIT_COMMITTEE / CRO. Authority is resolved from the ACTOR_ROLE master,
        // never a request-body role.
        int requiredRank = requiredConfirmRank(rating);
        if (actorAuthorityRank(actor) < requiredRank) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' is not authorised to confirm this score — needs "
                            + requiredAuthorityLabel(rating) + " authority or higher");
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
        // A confirmation always means "approved" — its downstream contract (DecisionService checks
        // confirmed()) is unchanged. When approval was required, the approval status flips APPROVED.
        if (Boolean.TRUE.equals(rating.getApprovalRequired())) {
            rating.setApprovalStatus("APPROVED");
        }
        audit.human(actor, "RATING_CONFIRMED", "Application", reference,
                "Approver confirmed final grade %s (%s authority)".formatted(
                        rating.getFinalGrade(), requiredAuthorityLabel(rating)),
                Map.of("final_grade", rating.getFinalGrade(),
                        "required_authority", requiredAuthorityLabel(rating),
                        "approval_status", rating.getApprovalStatus() == null ? "NOT_REQUIRED" : rating.getApprovalStatus()));
        Rating saved = ratings.save(rating);
        // Best-effort completion of the mirrored RATING_APPROVAL work-item (resolved by dedupeKey).
        if (taskClient != null) {
            taskClient.complete("RATING:" + reference, "Score approved by " + actor, actor);
        }
        return saved;
    }

    /**
     * Read view of the configurable scoring-approval routing on the latest rating (requireApproval,
     * requiredAuthority, status). Additive — does not touch any authoritative figure.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> scoringApproval(String reference) {
        Rating r = latestRating(reference);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationReference", reference);
        m.put("finalGrade", r.getFinalGrade());
        m.put("approvalRequired", r.getApprovalRequired());
        m.put("requiredAuthority", requiredAuthorityLabel(r));
        m.put("approvalStatus", r.getApprovalStatus() == null ? "NOT_REQUIRED" : r.getApprovalStatus());
        m.put("confirmed", r.isConfirmed());
        m.put("overridden", r.isOverridden());
        m.put("overrideNotches", r.getOverrideNotches());
        return m;
    }

    /**
     * Simulate the SCORING_APPROVAL_POLICY routing for hypothetical scored-rating parameters —
     * read-only, NON-persisting. Reuses the exact evaluation logic ({@link #resolveApproval}) that
     * the real rating path uses in {@link #applyScoringApproval}, so the simulated routing is
     * byte-identical to what a scored rating with the same params would receive. No rating is read,
     * written or mutated; nothing is persisted. Powers the Approval-Rules matrix "simulate routing"
     * panel. Returns {matchedRuleId, requireApproval, requiredAuthority}.
     */
    public Map<String, Object> simulateScoringApproval(Params params) {
        Resolution res = resolveApproval(params);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matchedRuleId", res.ruleId());
        m.put("requireApproval", res.requireApproval());
        m.put("requiredAuthority", res.requiredAuthority());
        return m;
    }

    // ------------------------------------------------------ scoring-approval routing (gate only)

    /**
     * Resolve the routing via the optional policy client, failing open to the flat CREDIT_OFFICER
     * gate when the (optional) bean is absent — the single evaluation path shared by the persisting
     * rating gate ({@link #applyScoringApproval}) and the read-only simulate endpoint.
     */
    private Resolution resolveApproval(Params params) {
        return scoringApprovalPolicy == null
                ? ScoringApprovalPolicyClient.FLAT
                : scoringApprovalPolicy.resolve(params);
    }

    /** Resolve + persist the SCORING_APPROVAL_POLICY routing onto a scored/overridden rating. */
    private void applyScoringApproval(Rating rating, Params params) {
        Resolution res = resolveApproval(params);
        rating.setApprovalRequired(res.requireApproval());
        rating.setRequiredAuthority(res.requiredAuthority());
        rating.setApprovalStatus(res.requireApproval() ? "PENDING_APPROVAL" : "NOT_REQUIRED");
    }

    /** Best-effort RATING_APPROVAL work-item, routed to a queue derived from the required authority. */
    private void raiseRatingApprovalTask(Rating r, String reason, String actor) {
        if (taskClient == null) return;
        if (!Boolean.TRUE.equals(r.getApprovalRequired()) || !"PENDING_APPROVAL".equals(r.getApprovalStatus())) {
            return;
        }
        String authority = requiredAuthorityLabel(r);
        taskClient.createTask("Rating", r.getApplicationReference(), "RATING_APPROVAL",
                "RATING_APPROVAL_" + authority, null, "RATING:" + r.getApplicationReference(), null, actor,
                Map.of("ratingId", r.getId(),
                        "requiredAuthority", authority,
                        "finalGrade", r.getFinalGrade() == null ? "" : r.getFinalGrade(),
                        "reason", reason == null ? "" : reason));
    }

    /** The required confirm authority string, floored at CREDIT_OFFICER (the legacy baseline gate). */
    private String requiredAuthorityLabel(Rating r) {
        String a = r.getRequiredAuthority();
        return (a == null || a.isBlank()) ? "CREDIT_OFFICER" : a.toUpperCase();
    }

    /** Required confirm rank = max(baseline CREDIT_OFFICER, policy-resolved authority rank). */
    private int requiredConfirmRank(Rating r) {
        int resolved = AUTHORITY_RANK.getOrDefault(requiredAuthorityLabel(r), BASELINE_AUTHORITY_RANK);
        return Math.max(BASELINE_AUTHORITY_RANK, resolved);
    }

    /**
     * The actor's maximum authority rank on the rating-confirm ladder, resolved from the roles the
     * ACTOR_ROLE master grants them (never a request-body role). A cold-start directory outage
     * fails open (MAX_VALUE), consistent with {@link #resolveNotchLimit(String)} / ActorDirectory.
     */
    private int actorAuthorityRank(String actor) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return Integer.MAX_VALUE;   // directory outage — fail open
        return actorRoles.stream()
                .mapToInt(role -> AUTHORITY_RANK.getOrDefault(role.toUpperCase(), 0))
                .max().orElse(0);
    }

    /** Coarse score band (STRONG / ADEQUATE / WEAK) — matches the model-scoring band cut-points. */
    private static String scoreBand(double score) {
        if (score >= 67) return "STRONG";
        if (score >= 45) return "ADEQUATE";
        return "WEAK";
    }

    /**
     * The actor's maximum override-notch authority, resolved from the roles the ACTOR_ROLE
     * master grants them (G1) — never a request-body role. A cold-start directory outage
     * fails open (99), consistent with ActorDirectory parity. The per-role notch ceilings
     * themselves come from the admin-configurable {@code OVERRIDE_ROLE} master (see
     * {@link #notchLimitsByRole()}); the built-in {@link #NOTCH_LIMITS} is the config-outage fallback.
     */
    private int resolveNotchLimit(String actor) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return 99;   // directory outage — fail open
        Map<String, Integer> limits = notchLimitsByRole();
        return actorRoles.stream()
                .mapToInt(r -> limits.getOrDefault(r.toUpperCase(), 0))
                .max().orElse(0);
    }

    /**
     * Per-role override-notch ceilings, resolved from the admin-configurable {@code OVERRIDE_ROLE}
     * CODE_VALUE master (the notch is each role's {@code score}). Falls back to the conservative
     * built-in {@link #NOTCH_LIMITS} — byte-identical to the seeded values — when config-service is
     * unreachable or the domain carries no scored values, so notch enforcement never depends on a
     * config outage. Editing the master's scores changes the enforced limits with no code change.
     */
    private Map<String, Integer> notchLimitsByRole() {
        Map<String, Integer> configured = masters.codeValueScores(OVERRIDE_ROLE_DOMAIN);
        return configured.isEmpty() ? NOTCH_LIMITS : configured;
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
