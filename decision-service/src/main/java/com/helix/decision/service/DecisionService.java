package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.model.Enums.DecisionOutcome;
import com.helix.common.notify.NotificationService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CreditInputsDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.client.UpstreamClient.RulePackDto;
import com.helix.decision.dto.Dtos.DecisionRequest;
import com.helix.decision.entity.CommitteeVote;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.repo.CommitteeVoteRepository;
import com.helix.decision.repo.CreditDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ActorDirectory roles;
    private final AuditService audit;
    private final ConditionPrecedentService conditionsPrecedent;
    private final CommitteeVoteRepository votes;
    private final NotificationService notifications;

    public DecisionService(CreditDecisionRepository decisions, UpstreamClient upstream,
                           DoaRouter router, ActorDirectory roles, AuditService audit,
                           ConditionPrecedentService conditionsPrecedent, CommitteeVoteRepository votes,
                           NotificationService notifications) {
        this.decisions = decisions;
        this.upstream = upstream;
        this.router = router;
        this.roles = roles;
        this.audit = audit;
        this.conditionsPrecedent = conditionsPrecedent;
        this.votes = votes;
        this.notifications = notifications;
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
        decision.setRoutedBy(actor);
        decision.setCommitteeMode(routing.committee());
        decision.setQuorumRequired(routing.committee() ? Math.max(1, routing.quorum()) : 1);
        decision.setStatus("PENDING_APPROVAL");
        CreditDecision saved = decisions.save(decision);

        audit.engine("APPROVAL_ROUTED", "Application", reference,
                "Routed to %s (%s)%s".formatted(routing.requiredAuthority(), routing.ruleApplied(),
                        routing.committee() ? " — COMMITTEE, quorum " + saved.getQuorumRequired() : ""),
                Map.of("requiredAuthority", routing.requiredAuthority(), "ruleApplied", routing.ruleApplied(),
                        "deviations", deviations, "committeeMode", routing.committee(),
                        "quorumRequired", saved.getQuorumRequired()));
        return saved;
    }

    @Transactional
    public CreditDecision decide(String reference, DecisionRequest req, String actor) {
        CreditDecision decision = latest(reference);
        if ("DECIDED".equals(decision.getStatus())) {
            throw ApiException.conflict("This application has already been decided");
        }
        DecisionOutcome outcome = parseOutcome(req.outcome());

        // AUTHORITY is resolved from the ACTOR_ROLE master, never the request body (G1).
        // req.role() is an advisory claim — recorded for the trail but never rank-checked.
        String qualifyingRole = requireAuthorityRank(actor, decision.getRequiredAuthority());
        requireConditionOnConditional(outcome, req);

        // Committee tiers (flagged in the DOA_MATRIX pack) require a quorum of distinct votes;
        // a single-authority tier keeps the original one-approver path (behaviour-preserving).
        if (decision.isCommitteeMode()) {
            return castCommitteeVote(reference, decision, req, outcome, qualifyingRole, actor);
        }

        decision.setOutcome(outcome.name());
        decision.setDeciderRole(qualifyingRole);
        decision.setDecidedBy(actor);
        decision.setDecidedAt(Instant.now());
        decision.setRationale(req.rationale());
        decision.setConditions(req.conditions() == null ? List.of() : req.conditions());
        decision.setDissent(req.dissent());
        decision.setStatus("DECIDED");
        CreditDecision saved = decisions.save(decision);
        materialiseConditions(reference, outcome, req, actor);

        audit.human(actor, "DECISION_RECORDED", "Application", reference,
                "%s by %s (%s; claimed %s)".formatted(outcome, actor, qualifyingRole, req.role()),
                Map.of("outcome", outcome.name(), "authority", qualifyingRole,
                        "claimedRole", req.role() == null ? "" : req.role(),
                        "conditions", saved.getConditions(), "amount", decision.getAmount()));
        return saved;
    }

    /**
     * Records one committee member's vote (SoD: the member who routed the deal cannot vote,
     * and no member may vote twice). The decision finalises only when the quorum of approving
     * votes is met; until then it stays PENDING_APPROVAL. On quorum the outcome is APPROVE, or
     * CONDITIONAL_APPROVE if any approving member attached conditions.
     */
    private CreditDecision castCommitteeVote(String reference, CreditDecision decision, DecisionRequest req,
                                             DecisionOutcome outcome, String qualifyingRole, String actor) {
        if (decision.getRoutedBy() != null && actor.equalsIgnoreCase(decision.getRoutedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "The member who routed this deal ('" + decision.getRoutedBy() + "') cannot also vote on it");
        }
        List<CommitteeVote> cast = votes.findByDecisionIdOrderByCastAtAsc(decision.getId());
        for (CommitteeVote v : cast) {
            if (actor.equalsIgnoreCase(v.getVoter())) {
                throw ApiException.forbiddenAutonomy(
                        "Committee member '" + actor + "' has already voted on this deal");
            }
        }
        boolean approving = outcome == DecisionOutcome.APPROVE || outcome == DecisionOutcome.CONDITIONAL_APPROVE;
        CommitteeVote vote = new CommitteeVote();
        vote.setApplicationReference(reference);
        vote.setDecisionId(decision.getId());
        vote.setVoter(actor);
        vote.setVoterRole(qualifyingRole);
        vote.setVoteOutcome(outcome.name());
        vote.setRationale(req.rationale());
        vote.setConditions(req.conditions() == null ? List.of() : req.conditions());
        vote.setDissent(!approving);
        votes.save(vote);
        audit.human(actor, "COMMITTEE_VOTE_CAST", "Application", reference,
                "%s vote by %s (%s)".formatted(outcome, actor, qualifyingRole),
                Map.of("outcome", outcome.name(), "voter", actor, "role", qualifyingRole));

        List<CommitteeVote> all = votes.findByDecisionIdOrderByCastAtAsc(decision.getId());
        long approvals = all.stream()
                .filter(v -> DecisionOutcome.APPROVE.name().equals(v.getVoteOutcome())
                        || DecisionOutcome.CONDITIONAL_APPROVE.name().equals(v.getVoteOutcome()))
                .count();
        if (approvals < decision.getQuorumRequired()) {
            try {
                notifications.enqueue(new NotificationService.Enqueue("COMMITTEE_QUORUM_PENDING",
                        "COMMITTEE_QUORUM_PENDING", "Application", reference,
                        "decision:" + decision.getId() + ":pending:" + approvals + "of" + decision.getQuorumRequired(),
                        null, Map.of("borrower", decision.getCounterpartyName(), "approvals", approvals,
                        "quorum", decision.getQuorumRequired(), "reference", reference,
                        "authority", decision.getRequiredAuthority()), null), actor);
            } catch (Exception e) {
                // never fail the vote on a notification error
            }
            return decision;   // quorum not yet met — still PENDING_APPROVAL
        }

        boolean anyConditional = all.stream()
                .anyMatch(v -> DecisionOutcome.CONDITIONAL_APPROVE.name().equals(v.getVoteOutcome()));
        DecisionOutcome finalOutcome = anyConditional
                ? DecisionOutcome.CONDITIONAL_APPROVE : DecisionOutcome.APPROVE;
        List<String> aggregated = new ArrayList<>();
        for (CommitteeVote v : all) {
            if (v.getConditions() != null) {
                for (String c : v.getConditions()) if (!aggregated.contains(c)) aggregated.add(c);
            }
        }
        decision.setOutcome(finalOutcome.name());
        decision.setDeciderRole(decision.getRequiredAuthority());
        decision.setDecidedBy("COMMITTEE");
        decision.setDecidedAt(Instant.now());
        decision.setRationale(req.rationale());
        decision.setConditions(aggregated);
        decision.setStatus("DECIDED");
        CreditDecision saved = decisions.save(decision);
        materialiseConditions(reference, finalOutcome, req, actor);

        audit.human(actor, "COMMITTEE_QUORUM_MET", "Application", reference,
                "%s by committee — %d approving vote(s) met quorum %d".formatted(
                        finalOutcome, approvals, decision.getQuorumRequired()),
                Map.of("outcome", finalOutcome.name(), "approvals", approvals,
                        "quorum", decision.getQuorumRequired(), "authority", decision.getRequiredAuthority()));
        // The same DECISION_RECORDED event the single-approver path emits, so downstream
        // audit consumers see a uniform finalisation marker.
        audit.human("COMMITTEE", "DECISION_RECORDED", "Application", reference,
                "%s by committee (%s)".formatted(finalOutcome, decision.getRequiredAuthority()),
                Map.of("outcome", finalOutcome.name(), "authority", decision.getRequiredAuthority(),
                        "conditions", saved.getConditions(), "amount", decision.getAmount()));
        return saved;
    }

    private void requireConditionOnConditional(DecisionOutcome outcome, DecisionRequest req) {
        if (outcome != DecisionOutcome.CONDITIONAL_APPROVE) return;
        boolean hasFreeText = req.conditions() != null && !req.conditions().isEmpty();
        boolean hasStructured = req.conditionsPrecedent() != null && !req.conditionsPrecedent().isEmpty();
        if (!hasFreeText && !hasStructured) {
            throw ApiException.badRequest("Conditional approval requires at least one condition");
        }
    }

    /**
     * Materialises the decision's structured conditions of sanction into the CP register when the
     * outcome approves (fully or conditionally). Inert unless {@code conditionsPrecedent} is supplied,
     * so every existing caller is unaffected.
     */
    private void materialiseConditions(String reference, DecisionOutcome outcome, DecisionRequest req, String actor) {
        boolean approves = outcome == DecisionOutcome.APPROVE || outcome == DecisionOutcome.CONDITIONAL_APPROVE;
        if (approves && req.conditionsPrecedent() != null && !req.conditionsPrecedent().isEmpty()) {
            conditionsPrecedent.materializeFromDecision(reference, req.conditionsPrecedent(), actor);
        }
    }

    @Transactional(readOnly = true)
    public List<CommitteeVote> votes(String reference) {
        CreditDecision d = latest(reference);
        return votes.findByDecisionIdOrderByCastAtAsc(d.getId());
    }

    /**
     * Resolve the decider's authority from the ACTOR_ROLE master (never the request body):
     * the actor must hold a role whose {@link Authorities#rank} is at least the routed
     * requirement. Returns the highest-ranked qualifying role (recorded as the decider role).
     * A cold-start directory outage fails open with the required authority, matching the
     * ActorDirectory / FacilityAmendmentService posture; an actor absent from a HEALTHY
     * directory resolves to no roles and is denied.
     */
    private String requireAuthorityRank(String actor, String requiredAuthority) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to decide");
        }
        int required = Authorities.rank(requiredAuthority);
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return requiredAuthority;   // directory outage — fail open (ActorDirectory logs WARN)
        return actorRoles.stream()
                .filter(r -> Authorities.rank(r) >= required)
                .max(Comparator.comparingInt(Authorities::rank))
                .orElseThrow(() -> ApiException.forbiddenAutonomy(
                        "This deal requires " + requiredAuthority + " authority — actor '" + actor
                        + "' holds " + actorRoles + " (insufficient rank); the request-body role is not trusted"));
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
