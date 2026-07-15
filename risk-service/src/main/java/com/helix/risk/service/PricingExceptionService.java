package com.helix.risk.service;

import com.helix.common.audit.AuditService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.risk.client.ConfigClient;
import com.helix.risk.client.OriginationClient;
import com.helix.risk.dto.CreditInputsDto;
import com.helix.risk.dto.RulePackDto;
import com.helix.risk.entity.CapitalResult;
import com.helix.risk.entity.PricingException;
import com.helix.risk.entity.PricingResult;
import com.helix.risk.entity.Rating;
import com.helix.risk.repo.PricingExceptionRepository;
import com.helix.risk.repo.PricingResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pricing-exception (concession) approval sub-workflow (PRD pricing-approval).
 * Routes a below-recommended rate to an authority tier sized to the concession and
 * the hurdle breach, and runs a one- or two-level maker-checker approval with
 * segregation of duties. The RAROC is re-computed at the proposed rate. The
 * authoritative {@link PricingResult} is never mutated — this is the governance
 * wrapper around a concession, not a re-pricing of the figure path.
 */
@Service
public class PricingExceptionService {

    private static final Logger log = LoggerFactory.getLogger(PricingExceptionService.class);

    /** Concession authority tiers, ranked (RELATIONSHIP_HEAD < CREDIT_OFFICER < CREDIT_HEAD < CREDIT_COMMITTEE). */
    private static final Map<String, Integer> TIER_RANK = Map.of(
            "RELATIONSHIP_HEAD", 1, "CREDIT_OFFICER", 2, "CREDIT_HEAD", 3, "CREDIT_COMMITTEE", 4);

    /** Maps the roles an actor may hold (ACTOR_ROLE master) onto the concession tier ladder. */
    private static final Map<String, Integer> ROLE_TIER_RANK = Map.of(
            "RM_HEAD", 1, "RELATIONSHIP_HEAD", 1, "CREDIT_OFFICER", 2, "CREDIT_HEAD", 3,
            "CREDIT_COMMITTEE", 4, "CRO", 4, "BOARD_COMMITTEE", 5);

    private final PricingExceptionRepository exceptions;
    private final PricingResultRepository pricingResults;
    private final RiskService risk;
    private final ConfigClient config;
    private final OriginationClient origination;
    private final AuditService audit;
    private final ActorDirectory actorDirectory;

    private final FtpService ftpService;
    private final com.helix.common.governance.AiGovernanceClient governance;

    /**
     * Optional best-effort case-management mirror; bean absent when the workflow-service
     * URL isn't configured. The domain concession approval (SoD, authority tiers, RAROC)
     * is authoritative and completely unaffected by the mirror — a mirror failure is
     * swallowed by the client and never reaches this transaction.
     */
    private final com.helix.common.workflow.TaskClient taskClient;

    public PricingExceptionService(PricingExceptionRepository exceptions, PricingResultRepository pricingResults,
                                   RiskService risk, ConfigClient config, OriginationClient origination,
                                   FtpService ftpService, AuditService audit,
                                   com.helix.common.governance.AiGovernanceClient governance,
                                   ActorDirectory actorDirectory,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false)
                                   com.helix.common.workflow.TaskClient taskClient) {
        this.exceptions = exceptions;
        this.pricingResults = pricingResults;
        this.risk = risk;
        this.config = config;
        this.origination = origination;
        this.ftpService = ftpService;
        this.audit = audit;
        this.governance = governance;
        this.actorDirectory = actorDirectory;
        this.taskClient = taskClient;
    }

    @Transactional
    public PricingException propose(String reference, double proposedRate, String reason, String actor) {
        if (proposedRate <= 0) throw ApiException.badRequest("proposedRate must be positive");
        // Governance gate first — the jurisdiction source (credit inputs) is the only
        // upstream call allowed before the gate; nothing else runs when disabled.
        CreditInputsDto in = origination.creditInputs(reference);
        governance.enforce(com.helix.common.governance.AiCapability.PRICING_EXCEPTION, in.jurisdiction());
        PricingResult pricing = pricingResults.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No pricing for " + reference + " — price the deal first"));
        Rating rating = risk.latestRating(reference);
        CapitalResult capital = risk.latestCapital(reference);
        RulePackDto pack = config.activePack(in.jurisdiction(), "PRICING");

        double recommended = pricing.getRecommendedRate();
        double hurdle = pack.number("hurdle_raroc", 0.15);
        double costOfFunds = ftpService.computeFtp(in.currency(), in.jurisdiction(),
                in.facilityType(), in.tenorMonths(), pack.number("cost_of_funds", 0.075)).ftp();
        double opexRate = pack.number("opex_rate", 0.010);
        double targetCapitalRatio = pack.number("target_capital_ratio", 0.12);
        double singleLevelBps = pack.number("exception_single_level_bps", 100);
        double twoLevelBps = pack.number("exception_two_level_bps", 200);

        double ead = rating.getEad();
        double el = rating.getPd() * rating.getLgd() * ead;
        double capitalCharge = capital.getRwa() * targetCapitalRatio;
        double cof = ead * costOfFunds;
        double opex = ead * opexRate;
        double income = proposedRate * ead;
        double proposedRaroc = capitalCharge > 0 ? (income - el - opex - cof) / capitalCharge : 0.0;
        boolean belowHurdle = proposedRaroc < hurdle - 1e-9;
        double concessionBps = round1((recommended - proposedRate) * 10_000);

        PricingException pe = new PricingException();
        pe.setApplicationReference(reference);
        pe.setRecommendedRate(round6(recommended));
        pe.setProposedRate(round6(proposedRate));
        pe.setConcessionBps(concessionBps);
        pe.setProposedRaroc(round6(proposedRaroc));
        pe.setHurdleRaroc(hurdle);
        pe.setBelowHurdle(belowHurdle);
        pe.setEad(round2(ead));
        pe.setReason(reason);
        pe.setProposedBy(actor);

        if (concessionBps <= 0) {
            // No concession (premium or par) — nothing to approve.
            pe.setRequiredAuthority("NONE");
            pe.setRequiredLevels(0);
            pe.setStatus("APPROVED");
            pe.setDecidedAt(Instant.now());
            pe.setDecisionComment("No concession — auto-approved");
        } else {
            // Two levels for a large concession, or a below-hurdle concession beyond the single-level band.
            int levels = (concessionBps > twoLevelBps || (belowHurdle && concessionBps > singleLevelBps)) ? 2 : 1;
            String authority;
            if (concessionBps <= 50 && !belowHurdle) authority = "RELATIONSHIP_HEAD";
            else if (concessionBps <= singleLevelBps) authority = "CREDIT_OFFICER";
            else if (concessionBps <= twoLevelBps) authority = "CREDIT_HEAD";
            else authority = "CREDIT_COMMITTEE";
            pe.setRequiredAuthority(authority);
            pe.setRequiredLevels(levels);
            pe.setStatus("PENDING_L1");
        }

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("recommendedRate", round6(recommended));
        b.put("proposedRate", round6(proposedRate));
        b.put("concessionBps", concessionBps);
        b.put("expectedLoss", round2(el));
        b.put("capitalCharge", round2(capitalCharge));
        b.put("costOfFunds", round2(cof));
        b.put("opex", round2(opex));
        b.put("proposedRaroc", round6(proposedRaroc));
        b.put("hurdleRaroc", hurdle);
        b.put("belowHurdle", belowHurdle);
        b.put("pricingPack", pack.code() + " v" + pack.version());
        pe.setBreakdown(b);

        PricingException saved = exceptions.save(pe);
        audit.human(actor, "PRICING_EXCEPTION_PROPOSED", "Application", reference,
                "Concession %.0fbps → %.2f%% (RAROC %.1f%%%s); %s authority, %d level(s)".formatted(
                        concessionBps, proposedRate * 100, proposedRaroc * 100,
                        belowHurdle ? ", below hurdle" : "", saved.getRequiredAuthority(), saved.getRequiredLevels()),
                Map.of("concessionBps", concessionBps, "belowHurdle", belowHurdle,
                        "authority", saved.getRequiredAuthority(), "status", saved.getStatus()));
        // Best-effort case-management mirror to the approver's queue when the concession
        // needs a human sign-off. Purely advisory task tracking — the authoritative
        // PricingException / PricingResult above is unchanged by this call.
        if (taskClient != null && "PENDING_L1".equals(saved.getStatus())) {
            taskClient.createTask("PricingException", reference, "PRICING_EXCEPTION_APPROVAL",
                    "PRICING_EXCEPTION_" + saved.getRequiredAuthority(), null,
                    "PE:" + saved.getId(), null, actor,
                    Map.of("pricingExceptionId", saved.getId(),
                            "authority", saved.getRequiredAuthority(),
                            "requiredLevels", saved.getRequiredLevels(),
                            "concessionBps", concessionBps,
                            "belowHurdle", belowHurdle));
        }
        return saved;
    }

    @Transactional
    public PricingException decide(Long id, boolean approve, String comment, String actor) {
        PricingException pe = exceptions.findById(id)
                .orElseThrow(() -> ApiException.notFound("No pricing exception: " + id));
        pe.setDecisionComment(comment);
        switch (pe.getStatus()) {
            case "PENDING_L1" -> {
                if (actor.equalsIgnoreCase(pe.getProposedBy())) {
                    throw ApiException.forbiddenAutonomy("Approver cannot be the proposer (segregation of duties)");
                }
                requireAuthorityTier(actor, pe);
                if (!approve) {
                    pe.setStatus("REJECTED");
                    pe.setDecidedAt(Instant.now());
                    audit.human(actor, "PRICING_EXCEPTION_REJECTED", "PricingException", String.valueOf(id),
                            "Rejected at L1", Map.of());
                    return exceptions.save(pe);
                }
                pe.setApproverL1(actor);
                if (pe.getRequiredLevels() <= 1) {
                    pe.setStatus("APPROVED");
                    pe.setDecidedAt(Instant.now());
                    audit.human(actor, "PRICING_EXCEPTION_APPROVED", "Application", pe.getApplicationReference(),
                            "Approved concession %.0fbps (single level)".formatted(pe.getConcessionBps()),
                            Map.of("concessionBps", pe.getConcessionBps()));
                } else {
                    pe.setStatus("PENDING_L2");
                    audit.human(actor, "PRICING_EXCEPTION_L1_APPROVED", "PricingException", String.valueOf(id),
                            "Level-1 approved; pending level-2", Map.of());
                }
                return exceptions.save(pe);
            }
            case "PENDING_L2" -> {
                if (actor.equalsIgnoreCase(pe.getProposedBy())) {
                    throw ApiException.forbiddenAutonomy("Approver cannot be the proposer (segregation of duties)");
                }
                if (actor.equalsIgnoreCase(pe.getApproverL1())) {
                    throw ApiException.forbiddenAutonomy("Level-2 approver must differ from level-1");
                }
                requireAuthorityTier(actor, pe);
                if (!approve) {
                    pe.setStatus("REJECTED");
                    pe.setDecidedAt(Instant.now());
                    audit.human(actor, "PRICING_EXCEPTION_REJECTED", "PricingException", String.valueOf(id),
                            "Rejected at L2", Map.of());
                    return exceptions.save(pe);
                }
                pe.setApproverL2(actor);
                pe.setStatus("APPROVED");
                pe.setDecidedAt(Instant.now());
                audit.human(actor, "PRICING_EXCEPTION_APPROVED", "Application", pe.getApplicationReference(),
                        "Approved concession %.0fbps (two levels)".formatted(pe.getConcessionBps()),
                        Map.of("concessionBps", pe.getConcessionBps()));
                return exceptions.save(pe);
            }
            default -> throw ApiException.conflict("Pricing exception already " + pe.getStatus());
        }
    }

    /**
     * G3: the approver must actually HOLD the concession's required authority tier
     * (resolved from the ACTOR_ROLE master), not merely be a distinct person from the
     * proposer / L1. A cold-start directory outage fails open with a WARN (ActorDirectory
     * parity); NONE / unranked tiers are no-ops.
     */
    private void requireAuthorityTier(String actor, PricingException pe) {
        String required = pe.getRequiredAuthority();
        if (required == null || "NONE".equals(required)) return;
        Integer requiredRank = TIER_RANK.get(required);
        if (requiredRank == null) return;
        Set<String> heldRoles = actorDirectory.rolesFor(actor);
        if (heldRoles == null) {
            log.warn("ACTOR_ROLE directory unavailable — allowing '{}' to decide {}-tier concession {} (fail-open)",
                    actor, required, pe.getId());
            return;
        }
        int held = heldRoles.stream()
                .map(r -> ROLE_TIER_RANK.getOrDefault(r == null ? "" : r.toUpperCase(), 0))
                .max(Integer::compareTo).orElse(0);
        if (held < requiredRank) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' does not hold the '" + required
                    + "' authority tier required to decide this concession (holds " + heldRoles
                    + ") — see the ACTOR_ROLE master");
        }
    }

    @Transactional(readOnly = true)
    public List<PricingException> list(String reference) {
        return exceptions.findByApplicationReferenceOrderByIdDesc(reference);
    }

    /**
     * Every concession queued for human action — both L1 and L2 lanes, so the L2
     * approver sees their queue. Previously this only returned PENDING_L1, leaving
     * two-level concessions invisible to the L2 approver after the L1 sign-off.
     */
    @Transactional(readOnly = true)
    public List<PricingException> pending() {
        return exceptions.findByStatusInOrderByIdDesc(List.of("PENDING_L1", "PENDING_L2"));
    }

    private double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double round6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }
}
