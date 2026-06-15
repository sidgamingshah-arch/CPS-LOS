package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.money.Money;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.rbac.ProtectedAction;
import com.helix.common.web.ApiException;
import com.helix.decision.client.LimitClient;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.client.UpstreamClient.RiskSummaryDto;
import com.helix.decision.client.UpstreamClient.RulePackDto;
import com.helix.decision.entity.Disbursement;
import com.helix.decision.entity.FacilityAmendment;
import com.helix.decision.repo.DisbursementRepository;
import com.helix.decision.repo.FacilityAmendmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-sanction facility amendment workflow: increase / decrease / tenor
 * extension, routed through the SAME DoA matrix that sanctioned the deal.
 *
 * <ul>
 *   <li><b>Routing</b> — required authority computed by {@link DoaRouter} on the
 *       POST-amendment total application exposure × the current rating grade. A
 *       bigger book needs a bigger signature; regime thresholds stay in the
 *       DOA_MATRIX rule pack, never in code.</li>
 *   <li><b>Approval</b> — the approver must hold a role whose
 *       {@link Authorities#rank} is at least the required authority's rank
 *       (RBAC), and differ from the proposer (SoD).</li>
 *   <li><b>Application</b> — on approval, origination's facility of record and the
 *       limit tree are updated with ABSOLUTE target values; both applies are
 *       retry-safe, so a partial failure leaves the amendment PROPOSED and the
 *       next approval attempt heals it.</li>
 * </ul>
 */
@Service
public class FacilityAmendmentService {

    private final FacilityAmendmentRepository repo;
    private final DisbursementRepository disbursements;
    private final DoaRouter doaRouter;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final ActorDirectory roles;
    private final AuditService audit;

    public FacilityAmendmentService(FacilityAmendmentRepository repo, DisbursementRepository disbursements,
                                    DoaRouter doaRouter, UpstreamClient upstream, LimitClient limits,
                                    ActorDirectory roles, AuditService audit) {
        this.repo = repo;
        this.disbursements = disbursements;
        this.doaRouter = doaRouter;
        this.upstream = upstream;
        this.limits = limits;
        this.roles = roles;
        this.audit = audit;
    }

    // ============================================================ propose

    @Transactional
    public FacilityAmendment propose(String applicationReference, String facilityRef,
                                     Double newAmount, Integer newTenorMonths,
                                     String reason, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to propose an amendment");
        }
        roles.require(actor, ProtectedAction.FACILITY_AMEND_PROPOSE);
        return proposeInternal(applicationReference, facilityRef, newAmount, newTenorMonths, reason, actor);
    }

    /**
     * RBAC-bypassing internal entry point. Called from CollectionsService when a
     * restructure flows through the amendment lane — the caller's own role check
     * (COLLECTIONS_UPDATE) already gates access. The amendment still routes through
     * the same DoA approver, so the actual approval signature is unchanged.
     */
    public FacilityAmendment proposeInternal(String applicationReference, String facilityRef,
                                             Double newAmount, Integer newTenorMonths,
                                             String reason, String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy("A named human actor is required to propose an amendment");
        }
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, facilityRef);
        if (facility == null) {
            throw ApiException.badRequest("No facility '" + facilityRef + "' on " + applicationReference);
        }
        if (!repo.findByApplicationReferenceAndFacilityRefAndStatus(
                applicationReference, facilityRef, "PROPOSED").isEmpty()) {
            throw ApiException.conflict("Facility " + facilityRef
                    + " already has a PROPOSED amendment — decide it before raising another");
        }
        boolean amountChange = newAmount != null && Math.abs(newAmount - facility.amount()) > 0.009;
        boolean tenorChange = newTenorMonths != null && newTenorMonths != facility.tenorMonths();
        if (!amountChange && !tenorChange) {
            throw ApiException.badRequest("Amendment must change the amount and/or the tenor");
        }
        if (newAmount != null && newAmount <= 0) {
            throw ApiException.badRequest("newAmount must be positive");
        }
        if (newTenorMonths != null && newTenorMonths <= 0) {
            throw ApiException.badRequest("newTenorMonths must be positive");
        }
        // A decrease can never cut below what's already committed on the facility
        // (drafted + authorised + released, net of cancels/rejects/reversals).
        if (amountChange && newAmount < facility.amount()) {
            double committed = disbursements
                    .findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(applicationReference, facilityRef)
                    .stream()
                    .filter(d -> !"REJECTED".equals(d.getStatus()) && !"CANCELLED".equals(d.getStatus())
                            && !"REVERSED".equals(d.getStatus()))
                    .map(Disbursement::getAmount).reduce(Money.ZERO, Money::add).doubleValue();
            if (newAmount < committed - 0.01) {
                throw ApiException.badRequest(
                        "Cannot decrease %s below its committed drawdowns: proposed %.2f < committed %.2f"
                                .formatted(facilityRef, newAmount, committed));
            }
        }

        // Route on the POST-amendment total application exposure × current grade.
        double routedExposure = 0;
        for (FacilityViewDto f : env.facilities()) {
            routedExposure += facilityRef.equals(f.reference()) && amountChange ? newAmount : f.amount();
        }
        RiskSummaryDto risk = upstream.riskSummaryOrNull(applicationReference);
        String grade = risk == null || risk.rating() == null ? null : risk.rating().finalGrade();
        RulePackDto doaPack = upstream.doaMatrix(env.jurisdiction());
        DoaRouter.Routing routing = doaRouter.route(routedExposure, grade, false, doaPack);

        String type = amountChange && tenorChange ? "MIXED"
                : tenorChange ? "TENOR_EXTENSION"
                : newAmount > facility.amount() ? "INCREASE" : "DECREASE";

        FacilityAmendment a = new FacilityAmendment();
        a.setApplicationReference(applicationReference);
        a.setFacilityRef(facilityRef);
        a.setAmendmentType(type);
        a.setCurrentAmount(Money.of(facility.amount()));
        a.setProposedAmount(amountChange ? Money.of(newAmount) : null);
        a.setCurrentTenorMonths(facility.tenorMonths());
        a.setProposedTenorMonths(tenorChange ? newTenorMonths : null);
        a.setRoutedExposure(Money.of(routedExposure));
        a.setCurrency(facility.currency() == null ? "INR" : facility.currency().toUpperCase());
        a.setReason(reason);
        a.setRequiredAuthority(routing.requiredAuthority());
        a.setRuleApplied(routing.ruleApplied());
        a.setProposedBy(actor);
        FacilityAmendment saved = repo.save(a);
        audit.human(actor, "FACILITY_AMENDMENT_PROPOSED", "FacilityAmendment", String.valueOf(saved.getId()),
                "%s on %s: amount %.2f -> %s, tenor %d -> %s; routed to %s on %.2f".formatted(
                        type, facilityRef, facility.amount(),
                        amountChange ? "%.2f".formatted(newAmount) : "unchanged",
                        facility.tenorMonths(),
                        tenorChange ? String.valueOf(newTenorMonths) : "unchanged",
                        routing.requiredAuthority(), routedExposure),
                Map.of("facilityRef", facilityRef, "type", type,
                        "requiredAuthority", routing.requiredAuthority(),
                        "routedExposure", routedExposure));
        return saved;
    }

    // ============================================================ approve / reject

    @Transactional
    public FacilityAmendment approve(Long id, String comment, String actor) {
        FacilityAmendment a = get(id);
        if (!"PROPOSED".equals(a.getStatus())) {
            throw ApiException.conflict("Amendment is " + a.getStatus());
        }
        if (actor == null || actor.isBlank() || actor.equals(a.getProposedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Amendment approver must be a named human different from the proposer ("
                    + a.getProposedBy() + ")");
        }
        requireAuthorityRank(actor, a.getRequiredAuthority());

        // Apply to the systems of record — absolute targets, both retry-safe. Any
        // failure throws, rolls this transaction back, and leaves the amendment
        // PROPOSED for a clean retry.
        String amendmentRef = "AMD-" + a.getApplicationReference() + "-" + a.getId();
        Double proposedD = a.getProposedAmount() == null ? null : a.getProposedAmount().doubleValue();
        upstream.applyFacilityAmendment(a.getApplicationReference(), a.getFacilityRef(),
                proposedD, a.getProposedTenorMonths(), amendmentRef, actor);
        limits.resyncFacility(a.getApplicationReference(), a.getFacilityRef(),
                proposedD, a.getProposedTenorMonths(), amendmentRef, actor);

        a.setStatus("APPROVED");
        a.setDecidedBy(actor);
        a.setDecidedAt(Instant.now());
        a.setDecisionComment(comment);
        a.setAppliedAt(Instant.now());
        FacilityAmendment saved = repo.save(a);
        audit.human(actor, "FACILITY_AMENDMENT_APPROVED", "FacilityAmendment", String.valueOf(id),
                "Approved %s on %s under %s authority — facility of record + limit tree updated (%s)".formatted(
                        a.getAmendmentType(), a.getFacilityRef(), a.getRequiredAuthority(), amendmentRef),
                Map.of("facilityRef", a.getFacilityRef(), "type", a.getAmendmentType(),
                        "requiredAuthority", a.getRequiredAuthority(), "amendmentRef", amendmentRef));
        return saved;
    }

    @Transactional
    public FacilityAmendment reject(Long id, String reason, String actor) {
        FacilityAmendment a = get(id);
        if (!"PROPOSED".equals(a.getStatus())) {
            throw ApiException.conflict("Amendment is " + a.getStatus());
        }
        if (actor == null || actor.isBlank() || actor.equals(a.getProposedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Amendment rejecter must be a named human different from the proposer");
        }
        // Any holder of a DoA-ladder authority may veto.
        if (bestAuthorityRank(actor) < Authorities.rank("RM_HEAD")) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' holds no delegated authority — cannot decide amendments");
        }
        a.setStatus("REJECTED");
        a.setDecidedBy(actor);
        a.setDecidedAt(Instant.now());
        a.setDecisionComment(reason);
        FacilityAmendment saved = repo.save(a);
        audit.human(actor, "FACILITY_AMENDMENT_REJECTED", "FacilityAmendment", String.valueOf(id),
                "Rejected %s on %s — %s".formatted(a.getAmendmentType(), a.getFacilityRef(), reason),
                Map.of("facilityRef", a.getFacilityRef(), "reason", reason == null ? "" : reason));
        return saved;
    }

    // ============================================================ reads

    @Transactional(readOnly = true)
    public FacilityAmendment get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No amendment: " + id));
    }

    @Transactional(readOnly = true)
    public List<FacilityAmendment> historyFor(String applicationReference) {
        return repo.findByApplicationReferenceOrderByIdDesc(applicationReference);
    }

    // ============================================================ helpers

    /**
     * The approver must hold a role whose authority rank is at least the required
     * authority's. With the directory cold-start-unavailable we fail open with a
     * WARN (consistent with ActorDirectory) — name-equality SoD still applies.
     */
    private void requireAuthorityRank(String actor, String requiredAuthority) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return;     // directory outage — ActorDirectory logs the WARN
        int required = Authorities.rank(requiredAuthority);
        int best = actorRoles.stream().mapToInt(Authorities::rank).max().orElse(-1);
        if (best < required) {
            throw ApiException.forbiddenAutonomy(
                    "Amendment requires " + requiredAuthority + " authority — actor '" + actor
                    + "' holds " + actorRoles + " (insufficient rank)");
        }
    }

    private int bestAuthorityRank(String actor) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return Integer.MAX_VALUE;   // directory outage — fail open
        return actorRoles.stream().mapToInt(Authorities::rank).max().orElse(-1);
    }

    private FacilityViewDto findFacility(DealEnvelopeDto env, String facilityRef) {
        if (env.facilities() == null) return null;
        for (FacilityViewDto f : env.facilities()) {
            if (f.reference() != null && f.reference().equals(facilityRef)) return f;
        }
        return null;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
