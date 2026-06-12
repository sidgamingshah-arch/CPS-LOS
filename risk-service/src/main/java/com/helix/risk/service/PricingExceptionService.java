package com.helix.risk.service;

import com.helix.common.audit.AuditService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final PricingExceptionRepository exceptions;
    private final PricingResultRepository pricingResults;
    private final RiskService risk;
    private final ConfigClient config;
    private final OriginationClient origination;
    private final AuditService audit;

    private final FtpService ftpService;
    private final com.helix.common.governance.AiGovernanceClient governance;

    public PricingExceptionService(PricingExceptionRepository exceptions, PricingResultRepository pricingResults,
                                   RiskService risk, ConfigClient config, OriginationClient origination,
                                   FtpService ftpService, AuditService audit,
                                   com.helix.common.governance.AiGovernanceClient governance) {
        this.exceptions = exceptions;
        this.pricingResults = pricingResults;
        this.risk = risk;
        this.config = config;
        this.origination = origination;
        this.ftpService = ftpService;
        this.audit = audit;
        this.governance = governance;
    }

    @Transactional
    public PricingException propose(String reference, double proposedRate, String reason, String actor) {
        if (proposedRate <= 0) throw ApiException.badRequest("proposedRate must be positive");
        PricingResult pricing = pricingResults.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No pricing for " + reference + " — price the deal first"));
        Rating rating = risk.latestRating(reference);
        CapitalResult capital = risk.latestCapital(reference);
        CreditInputsDto in = origination.creditInputs(reference);
        governance.enforce(com.helix.common.governance.AiCapability.PRICING_EXCEPTION, in.jurisdiction());
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
