package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.DecisionOutcome;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CreditInputsDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.client.UpstreamClient.RulePackDto;
import com.helix.decision.dto.Dtos.DecisionRequest;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.repo.CreditDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Credit approval workflow (PRD §8). Routes per the DoA matrix and captures a
 * named human decision with conditions and dissent. AI never approves; it may
 * only draft the committee note for human editing.
 */
@Service
public class DecisionService {

    private final CreditDecisionRepository decisions;
    private final UpstreamClient upstream;
    private final DoaRouter router;
    private final AuditService audit;

    public DecisionService(CreditDecisionRepository decisions, UpstreamClient upstream,
                           DoaRouter router, AuditService audit) {
        this.decisions = decisions;
        this.upstream = upstream;
        this.router = router;
        this.audit = audit;
    }

    @Transactional
    public CreditDecision initiate(String reference, String actor) {
        CreditInputsDto inputs = upstream.creditInputs(reference);
        RiskSummaryDto risk = upstream.riskSummary(reference);
        if (risk.rating() == null) {
            throw ApiException.conflict("Cannot route for approval: no rating exists yet");
        }
        if (!risk.rating().confirmed()) {
            throw ApiException.conflict("Cannot route for approval: rating is not yet approver-confirmed");
        }

        List<String> deviations = new ArrayList<>();
        if (risk.pricing() != null && risk.pricing().belowHurdle()) {
            deviations.add("Below RAROC hurdle (%.1f%% vs %.1f%%)".formatted(
                    risk.pricing().raroc() * 100, risk.pricing().hurdleRaroc() * 100));
        }
        if (risk.rating().overridden()) {
            deviations.add("Rating overridden (%s -> %s)".formatted(
                    risk.rating().modelGrade(), risk.rating().finalGrade()));
        }
        if (risk.rating().escalated()) {
            deviations.add("Rating override required escalation");
        }

        RulePackDto doaPack = upstream.doaMatrix(inputs.jurisdiction());
        DoaRouter.Routing routing = router.route(inputs.requestedAmount(), risk.rating().finalGrade(),
                !deviations.isEmpty(), doaPack);

        CreditDecision decision = new CreditDecision();
        decision.setApplicationReference(reference);
        decision.setCounterpartyName(inputs.counterpartyName());
        decision.setAmount(inputs.requestedAmount());
        decision.setCurrency(inputs.currency());
        decision.setSegment(inputs.segment());
        decision.setFinalGrade(risk.rating().finalGrade());
        decision.setRaroc(risk.pricing() == null ? 0.0 : risk.pricing().raroc());
        decision.setBelowHurdle(risk.pricing() != null && risk.pricing().belowHurdle());
        decision.setRatingEscalated(risk.rating().escalated());
        decision.setRequiredAuthority(routing.requiredAuthority());
        decision.setDeviations(deviations);
        decision.setStatus("PENDING_APPROVAL");
        CreditDecision saved = decisions.save(decision);

        audit.engine("APPROVAL_ROUTED", "Application", reference,
                "Routed to %s (%s)".formatted(routing.requiredAuthority(), routing.ruleApplied()),
                Map.of("requiredAuthority", routing.requiredAuthority(), "ruleApplied", routing.ruleApplied(),
                        "deviations", deviations));
        return saved;
    }

    @Transactional
    public CreditDecision decide(String reference, DecisionRequest req, String actor) {
        CreditDecision decision = latest(reference);
        if ("DECIDED".equals(decision.getStatus())) {
            throw ApiException.conflict("This application has already been decided");
        }
        DecisionOutcome outcome = parseOutcome(req.outcome());
        String role = req.role().toUpperCase();

        // The decider must hold authority at least equal to the routed requirement (PRD §8 SoD).
        if (Authorities.rank(role) < Authorities.rank(decision.getRequiredAuthority())) {
            throw ApiException.forbiddenAutonomy(
                    "%s lacks authority; this deal requires %s".formatted(role, decision.getRequiredAuthority()));
        }
        if (outcome == DecisionOutcome.CONDITIONAL_APPROVE
                && (req.conditions() == null || req.conditions().isEmpty())) {
            throw ApiException.badRequest("Conditional approval requires at least one condition");
        }

        decision.setOutcome(outcome.name());
        decision.setDeciderRole(role);
        decision.setDecidedBy(actor);
        decision.setDecidedAt(Instant.now());
        decision.setRationale(req.rationale());
        decision.setConditions(req.conditions() == null ? List.of() : req.conditions());
        decision.setDissent(req.dissent());
        decision.setStatus("DECIDED");
        CreditDecision saved = decisions.save(decision);

        audit.human(actor, "DECISION_RECORDED", "Application", reference,
                "%s by %s (%s)".formatted(outcome, actor, role),
                Map.of("outcome", outcome.name(), "authority", role,
                        "conditions", saved.getConditions(), "amount", decision.getAmount()));
        return saved;
    }

    /** AI [C] committee-note draft, grounded in and citing the deal facts (PRD §8, US-8.3). */
    @Transactional(readOnly = true)
    public Map<String, String> committeeNote(String reference) {
        CreditDecision d = latest(reference);
        String note = ("""
                CREDIT COMMITTEE NOTE (DRAFT — AI-generated, requires human edit and sign-off)
                Application: %s   Borrower: %s
                Facility: %.0f %s   Segment: %s   Final grade: %s
                RAROC: %.1f%%%s
                Routing: requires %s under the delegated-authority matrix.
                Deviations: %s
                Recommendation: subject to committee judgement. This draft cites only platform figures;
                no facts or numbers were generated by the model.
                """).formatted(
                d.getApplicationReference(), d.getCounterpartyName(), d.getAmount(), d.getCurrency(),
                d.getSegment(), d.getFinalGrade(), d.getRaroc() * 100,
                d.isBelowHurdle() ? " (BELOW HURDLE)" : "",
                d.getRequiredAuthority(),
                d.getDeviations() == null || d.getDeviations().isEmpty() ? "none" : String.join("; ", d.getDeviations()));
        return Map.of("draft", note);
    }

    @Transactional(readOnly = true)
    public CreditDecision latest(String reference) {
        return decisions.findFirstByApplicationReferenceOrderByCreatedAtDesc(reference)
                .orElseThrow(() -> ApiException.notFound("No decision routed for " + reference));
    }

    @Transactional(readOnly = true)
    public List<CreditDecision> history(String reference) {
        return decisions.findByApplicationReferenceOrderByCreatedAtDesc(reference);
    }

    private DecisionOutcome parseOutcome(String value) {
        try {
            return DecisionOutcome.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown outcome: " + value);
        }
    }
}
