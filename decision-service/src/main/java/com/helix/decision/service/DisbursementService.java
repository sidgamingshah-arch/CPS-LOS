package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.money.Money;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.rbac.ProtectedAction;
import com.helix.common.web.ApiException;
import com.helix.decision.client.LimitClient;
import com.helix.decision.client.LimitClient.UtilisationResponseDto;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.client.UpstreamClient.FacilityViewDto;
import com.helix.decision.dto.CpDtos.CpBlocker;
import com.helix.decision.dto.CpDtos.CpGateResult;
import com.helix.decision.entity.Disbursement;
import com.helix.decision.repo.DisbursementRepository;
import com.helix.decision.repo.RepaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-disbursement workflow: request a tranche, authorise it (which runs the CP
 * gate), and release it (which books the corresponding limit utilisation). One
 * {@code Disbursement} row per draw — multi-tranche PF, partial WC, and revolver
 * use all map naturally to multiple sequential rows on the same facility.
 *
 * <p>SoD: requester ≠ authoriser ≠ releaser — and the releaser must also differ
 * from the requester, so no two-role shuffle can put the same person on both ends
 * of a draw. Each transition is stamped against a named actor on the audit trail;
 * a missing/blank actor is itself an SoD violation (we never fall back to an
 * anonymous default on a money-moving path). {@link ApiException#forbiddenAutonomy}
 * fires the moment a maker tries to be the checker, matching the platform convention.</p>
 */
@Service
public class DisbursementService {

    private final DisbursementRepository repo;
    private final RepaymentRepository repayments;
    private final ConditionPrecedentService cps;
    private final PfService pf;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final ActorDirectory roles;
    private final AuditService audit;
    /** Bank FX margin (bps) applied over the mid rate on cross-currency draws. */
    private final double fxSpreadBps;

    public DisbursementService(DisbursementRepository repo, RepaymentRepository repayments,
                               ConditionPrecedentService cps, PfService pf,
                               UpstreamClient upstream, LimitClient limits,
                               ActorDirectory roles, AuditService audit,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${helix.disbursement.fx-spread-bps:25}") double fxSpreadBps) {
        this.repo = repo;
        this.repayments = repayments;
        this.cps = cps;
        this.pf = pf;
        this.upstream = upstream;
        this.limits = limits;
        this.roles = roles;
        this.audit = audit;
        this.fxSpreadBps = fxSpreadBps;
    }

    // ============================================================ request

    /**
     * Drafts a drawdown request. Validates the facility exists on the application,
     * the amount sits within the unutilised headroom, and the currency matches the
     * facility (cross-currency disbursements would need an FX conversion step we
     * don't model in this iteration).
     */
    @Transactional
    public Disbursement request(String applicationReference, String facilityRef,
                                double amount, String currency, String purpose, String narrative,
                                Integer milestoneSequence, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_REQUEST);
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, facilityRef);
        if (facility == null) {
            throw ApiException.badRequest("No facility '" + facilityRef + "' on " + applicationReference);
        }
        if (currency == null || currency.isBlank()) currency = facility.currency();
        // Cross-currency: if the request is in a non-facility currency, convert to
        // facility currency at the platform FX rate. The limit ledger, headroom check,
        // and downstream booking all stay in facility currency; we keep the original
        // requested amount + currency + applied rate on the row for the audit trail.
        String requestedCurrency = currency.toUpperCase();
        double requestedAmount = amount;
        Double fxRate = null;
        double facilityAmount = amount;
        String facilityCcy = facility.currency() == null ? "INR" : facility.currency().toUpperCase();
        if (!requestedCurrency.equalsIgnoreCase(facilityCcy)) {
            LimitClient.FxQuote q = limits.fxQuote(requestedCurrency, facilityCcy, requestedAmount);
            double effRate = applySpread(q.rate());   // mid + bank FX margin
            fxRate = round6(effRate);
            facilityAmount = requestedAmount * effRate;
        }
        double used = repo.findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(applicationReference, facilityRef)
                .stream()
                .filter(d -> !"REJECTED".equals(d.getStatus()) && !"CANCELLED".equals(d.getStatus())
                        && !"REVERSED".equals(d.getStatus()))
                .map(Disbursement::getAmount).reduce(Money.ZERO, Money::add).doubleValue();
        if (used + facilityAmount > facility.amount() + 0.01) {
            throw ApiException.badRequest("Drawdown of " + facilityAmount + " " + facilityCcy
                    + " would exceed facility " + facilityRef + " (sanctioned " + facility.amount()
                    + ", used " + used + ")");
        }
        int nextNo = repo.findFirstByApplicationReferenceAndFacilityRefOrderByDrawdownNoDesc(applicationReference, facilityRef)
                .map(Disbursement::getDrawdownNo).orElse(0) + 1;

        Disbursement d = new Disbursement();
        d.setApplicationReference(applicationReference);
        d.setFacilityRef(facilityRef);
        d.setDrawdownNo(nextNo);
        d.setAmount(Money.of(facilityAmount));
        d.setCurrency(facilityCcy);
        d.setRequestedAmount(round2(requestedAmount));
        d.setRequestedCurrency(requestedCurrency);
        d.setFxRate(fxRate);
        d.setBaseAmount(Money.of(facilityAmount));  // updated on release
        d.setPurpose(purpose);
        d.setNarrative(narrative);
        d.setStatus("DRAFT");
        d.setRequestedBy(actor);
        d.setMilestoneSequence(milestoneSequence);
        Disbursement saved = repo.save(d);
        String fxNote = fxRate == null ? ""
                : " (converted from %.2f %s @ %.4f)".formatted(requestedAmount, requestedCurrency, fxRate);
        audit.human(actor, "DISBURSEMENT_REQUESTED", "Disbursement", String.valueOf(saved.getId()),
                "Drawdown #%d of %.2f %s requested on %s%s".formatted(nextNo, facilityAmount, facilityCcy, facilityRef, fxNote),
                Map.of("applicationReference", applicationReference, "facilityRef", facilityRef,
                        "amount", facilityAmount, "currency", facilityCcy,
                        "requestedAmount", requestedAmount, "requestedCurrency", requestedCurrency,
                        "fxRate", fxRate == null ? "" : fxRate,
                        "drawdownNo", nextNo));
        return saved;
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round6(double v) { return Math.round(v * 1_000_000.0) / 1_000_000.0; }

    /** Applies the bank's FX margin over the mid rate (customer pays the spread). */
    private double applySpread(double midRate) {
        return midRate * (1.0 + fxSpreadBps / 10_000.0);
    }

    /**
     * Re-quotes a cross-currency draw at the live rate when it releases. No-op for
     * same-currency draws. Updates the facility-currency amount, base amount and the
     * applied rate in place, re-checks headroom (excluding this draw), and stamps a
     * {@code DISBURSEMENT_FX_REQUOTED} event when the rate has moved.
     */
    private void requoteFxAtRelease(Disbursement d) {
        if (d.getFxRate() == null || d.getRequestedCurrency() == null
                || d.getRequestedCurrency().equalsIgnoreCase(d.getCurrency())) {
            return;
        }
        LimitClient.FxQuote q = limits.fxQuote(d.getRequestedCurrency(), d.getCurrency(), d.getRequestedAmount());
        double effRate = applySpread(q.rate());
        double newAmount = round2(d.getRequestedAmount() * effRate);
        if (Math.abs(newAmount - Money.asDouble(d.getAmount())) <= 0.01) {
            return;   // rate unchanged within rounding — nothing to do
        }
        DealEnvelopeDto env = upstream.envelope(d.getApplicationReference());
        FacilityViewDto facility = findFacility(env, d.getFacilityRef());
        double sanctioned = facility == null ? Double.MAX_VALUE : facility.amount();
        double otherUsed = repo.findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(
                        d.getApplicationReference(), d.getFacilityRef()).stream()
                .filter(o -> !o.getId().equals(d.getId()))
                .filter(o -> !"REJECTED".equals(o.getStatus()) && !"CANCELLED".equals(o.getStatus())
                        && !"REVERSED".equals(o.getStatus()))
                .map(Disbursement::getAmount).reduce(Money.ZERO, Money::add).doubleValue();
        if (otherUsed + newAmount > sanctioned + 0.01) {
            throw ApiException.conflict(("FX moved against the draw at release: re-quoted %.2f %s exceeds "
                    + "remaining headroom on %s (sanctioned %.2f, other used %.2f) — amend or re-request")
                    .formatted(newAmount, d.getCurrency(), d.getFacilityRef(), sanctioned, otherUsed));
        }
        double oldAmount = Money.asDouble(d.getAmount());
        double oldRate = d.getFxRate();
        d.setAmount(Money.of(newAmount));
        d.setBaseAmount(Money.of(newAmount));
        d.setFxRate(round6(effRate));
        audit.engine("DISBURSEMENT_FX_REQUOTED", "Disbursement", String.valueOf(d.getId()),
                "Release re-quote: %.2f %s @ %.4f -> %.2f %s @ %.4f".formatted(
                        oldAmount, d.getCurrency(), oldRate, newAmount, d.getCurrency(), effRate),
                Map.of("requestedAmount", d.getRequestedAmount(), "requestedCurrency", d.getRequestedCurrency(),
                        "oldAmount", oldAmount, "newAmount", newAmount,
                        "oldRate", oldRate, "newRate", round6(effRate)));
    }

    /**
     * Every disbursement transition needs a named human. A blank actor would
     * otherwise satisfy (or dodge) the equality-based SoD checks below.
     */
    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy(
                    "A named human actor (X-Actor header) is required on disbursement actions");
        }
    }

    // ============================================================ amend / cancel

    /**
     * Edits a DRAFT drawdown. Only the original requester may amend their own draft;
     * any null field is left unchanged. Amount/currency changes re-run the FX
     * conversion + headroom check from scratch.
     */
    @Transactional
    public Disbursement amend(Long id, Double amount, String currency, String purpose,
                              String narrative, Integer milestoneSequence, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_AMEND);
        Disbursement d = get(id);
        if (!"DRAFT".equals(d.getStatus())) {
            throw ApiException.conflict("Amend is only allowed on DRAFT — current status " + d.getStatus());
        }
        if (!actor.equals(d.getRequestedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the original requester (" + d.getRequestedBy() + ") may amend this draft");
        }
        // If amount/currency moves, recompute against the facility (FX + headroom).
        boolean monetaryChange = amount != null || (currency != null && !currency.isBlank()
                && !currency.equalsIgnoreCase(d.getRequestedCurrency()));
        if (monetaryChange) {
            DealEnvelopeDto env = upstream.envelope(d.getApplicationReference());
            FacilityViewDto facility = findFacility(env, d.getFacilityRef());
            if (facility == null) throw ApiException.badRequest("Facility gone");
            String reqCcy = currency == null || currency.isBlank()
                    ? d.getRequestedCurrency() : currency.toUpperCase();
            double reqAmt = amount == null ? d.getRequestedAmount() : amount;
            String facCcy = facility.currency() == null ? "INR" : facility.currency().toUpperCase();
            double facAmt = reqAmt;
            Double fx = null;
            if (!reqCcy.equalsIgnoreCase(facCcy)) {
                LimitClient.FxQuote q = limits.fxQuote(reqCcy, facCcy, reqAmt);
                double effRate = applySpread(q.rate());   // mid + bank FX margin
                facAmt = reqAmt * effRate;
                fx = round6(effRate);
            }
            // Re-check headroom excluding this row.
            double used = repo.findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(
                    d.getApplicationReference(), d.getFacilityRef()).stream()
                    .filter(other -> !other.getId().equals(d.getId()))
                    .filter(other -> !"REJECTED".equals(other.getStatus())
                            && !"CANCELLED".equals(other.getStatus())
                            && !"REVERSED".equals(other.getStatus()))
                    .map(Disbursement::getAmount).reduce(Money.ZERO, Money::add).doubleValue();
            if (used + facAmt > facility.amount() + 0.01) {
                throw ApiException.badRequest("Amended drawdown of " + facAmt + " " + facCcy
                        + " would exceed facility (sanctioned " + facility.amount() + ", other used " + used + ")");
            }
            d.setRequestedAmount(round2(reqAmt));
            d.setRequestedCurrency(reqCcy);
            d.setFxRate(fx);
            d.setAmount(Money.of(facAmt));
            d.setCurrency(facCcy);
            d.setBaseAmount(Money.of(facAmt));
        }
        if (purpose != null) d.setPurpose(purpose);
        if (narrative != null) d.setNarrative(narrative);
        if (milestoneSequence != null) d.setMilestoneSequence(milestoneSequence);
        Disbursement saved = repo.save(d);
        audit.human(actor, "DISBURSEMENT_AMENDED", "Disbursement", String.valueOf(id),
                "Amended drawdown #%d on %s — new %.2f %s".formatted(
                        d.getDrawdownNo(), d.getFacilityRef(), d.getAmount(), d.getCurrency()),
                Map.of("facilityRef", d.getFacilityRef(), "amount", d.getAmount(),
                        "currency", d.getCurrency()));
        return saved;
    }

    /**
     * Voluntary cancellation. Distinct from {@link #reject} — that's the checker's
     * veto. Allowed in DRAFT or AUTHORIZED (before money moves); a RELEASED draw
     * needs a reversal, not a cancel.
     *
     * <p>SoD: a DRAFT belongs to its requester, so only they may withdraw it. Once
     * AUTHORIZED, the draw carries a second actor's approval — the requester can no
     * longer unilaterally void it; cancellation then requires the authoriser.</p>
     */
    @Transactional
    public Disbursement cancel(Long id, String reason, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_CANCEL);
        Disbursement d = get(id);
        if ("RELEASED".equals(d.getStatus())) {
            throw ApiException.conflict("Cannot cancel a RELEASED drawdown — use a reversal");
        }
        if ("CANCELLED".equals(d.getStatus()) || "REJECTED".equals(d.getStatus())) {
            throw ApiException.conflict("Drawdown already " + d.getStatus());
        }
        if ("AUTHORIZED".equals(d.getStatus())) {
            if (!actor.equals(d.getAuthorizedBy())) {
                throw ApiException.forbiddenAutonomy(
                        "An AUTHORIZED drawdown can only be cancelled by its authoriser ("
                        + d.getAuthorizedBy() + ") — the approval cannot be voided unilaterally; "
                        + "a checker may also use /reject");
            }
        } else if (!actor.equals(d.getRequestedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Only the original requester (" + d.getRequestedBy()
                    + ") may cancel this draft; a checker should use /reject");
        }
        d.setStatus("CANCELLED");
        d.setCancelledBy(actor);
        d.setCancelledReason(reason);
        d.setCancelledAt(Instant.now());
        Disbursement saved = repo.save(d);
        audit.human(actor, "DISBURSEMENT_CANCELLED", "Disbursement", String.valueOf(id),
                "Cancelled drawdown #%d on %s — %s".formatted(
                        d.getDrawdownNo(), d.getFacilityRef(), reason == null ? "(no reason)" : reason),
                Map.of("facilityRef", d.getFacilityRef(),
                        "reason", reason == null ? "" : reason));
        return saved;
    }

    // ============================================================ authorise (THE CP GATE)

    /**
     * Authorises a DRAFT drawdown. This is the pre-disbursement gate: if any
     * mandatory CP on the facility is still OPEN (or REJECTED), the call is
     * blocked with a 403 and the blocker list is included in the error payload.
     *
     * <p>SoD: the authoriser must differ from the requester.</p>
     */
    @Transactional
    public Disbursement authorize(Long id, String note, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_AUTHORIZE);
        Disbursement d = get(id);
        if (!"DRAFT".equals(d.getStatus())) {
            throw ApiException.conflict("Disbursement is " + d.getStatus());
        }
        if (actor.equals(d.getRequestedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Drawdown authoriser must differ from requester (" + actor + ")");
        }
        CpGateResult gate = cps.gate(d.getApplicationReference(), d.getFacilityRef());
        if (!gate.canDrawdown()) {
            // Surface the blockers as a structured 403 payload — the UI renders them
            // verbatim so credit ops know exactly what to chase before retrying.
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facilityRef", d.getFacilityRef());
            payload.put("mandatoryOpen", gate.mandatoryOpen());
            payload.put("mandatoryTotal", gate.mandatoryTotal());
            payload.put("blockers", gate.blockers());
            String msg = "Cannot authorise drawdown — " + gate.mandatoryOpen() + " of "
                    + gate.mandatoryTotal() + " mandatory CP(s) still OPEN on " + d.getFacilityRef()
                    + ": " + summarise(gate.blockers());
            audit.engine("DISBURSEMENT_BLOCKED_BY_CP", "Disbursement", String.valueOf(id),
                    msg, payload);
            throw ApiException.forbiddenAutonomy(msg);
        }
        // PF facilities layer a per-tranche gate on top of the CP gate: the named
        // milestone must be LIE-certified and all reserve accounts funded. No-op for
        // non-PF facilities (those with no milestones defined).
        pf.assertDrawable(d.getApplicationReference(), d.getFacilityRef(), d.getMilestoneSequence());

        d.setStatus("AUTHORIZED");
        d.setAuthorizedBy(actor);
        d.setAuthorizedAt(Instant.now());
        Disbursement saved = repo.save(d);
        audit.human(actor, "DISBURSEMENT_AUTHORIZED", "Disbursement", String.valueOf(id),
                "Authorised drawdown #%d on %s%s".formatted(
                        d.getDrawdownNo(), d.getFacilityRef(), note == null ? "" : " — " + note),
                Map.of("applicationReference", d.getApplicationReference(),
                        "facilityRef", d.getFacilityRef(),
                        "amount", d.getAmount(), "drawdownNo", d.getDrawdownNo()));
        return saved;
    }

    private String summarise(List<CpBlocker> blockers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, blockers.size()); i++) {
            if (i > 0) sb.append("; ");
            CpBlocker b = blockers.get(i);
            sb.append(b.code()).append(" ").append(b.title());
        }
        if (blockers.size() > 3) sb.append(" (+").append(blockers.size() - 3).append(" more)");
        return sb.toString();
    }

    // ============================================================ release

    /**
     * Releases an AUTHORIZED drawdown — calls limit-service to book a UTILISE on
     * the facility's limit node and marks the disbursement RELEASED. SoD: the
     * releaser must differ from both the authoriser and the original requester
     * (three named humans on every funded draw).
     *
     * <p>Retry safety: limit-service's UTILISE is idempotent on the
     * {@code transactionRef} we send (the {@code DISB-…} string built below), and
     * syndication allocation + PF milestone marking are idempotent on their own
     * keys. So if the limit booking succeeds but anything after it fails locally
     * — rolling back the disbursement row — the next release attempt is a no-op
     * on the remote ledger and re-runs the local steps cleanly.</p>
     */
    @Transactional
    public Disbursement release(Long id, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_RELEASE);
        Disbursement d = get(id);
        if (!"AUTHORIZED".equals(d.getStatus())) {
            throw ApiException.conflict("Disbursement is " + d.getStatus() + " — authorise first");
        }
        if (actor.equals(d.getAuthorizedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Drawdown releaser must differ from authoriser (" + actor + ")");
        }
        if (actor.equals(d.getRequestedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Drawdown releaser must differ from the original requester (" + actor
                    + ") — request, authorise and release need three distinct humans");
        }
        // Cross-currency draws re-quote FX at RELEASE time — the rate may have moved
        // since the draft was raised, so the facility-currency amount that actually
        // books must reflect the live rate (the request-time figure was indicative).
        // The bank's FX margin is applied, and headroom is re-checked on the refreshed
        // amount; an adverse move that no longer fits the facility blocks the release.
        requoteFxAtRelease(d);

        LimitClient.LimitNodeDto node = limits.nodeForFacility(d.getApplicationReference(), d.getFacilityRef());
        if (node == null) {
            throw ApiException.conflict("No limit node for facility " + d.getFacilityRef()
                    + " on " + d.getApplicationReference() + " — build the limit tree first");
        }
        String txnRef = "DISB-" + d.getApplicationReference() + "-" + d.getFacilityRef() + "-" + d.getDrawdownNo();
        UtilisationResponseDto response = limits.utilise(node.cif(), node.reference(),
                Money.asDouble(d.getAmount()), d.getCurrency(), txnRef, actor);
        if (response == null || !response.success()) {
            // Don't flip status; surface the limit failure so the UI shows the cause.
            String reason = response == null ? "no response from limit-service"
                    : (response.results() == null || response.results().isEmpty()
                            ? "no result rows"
                            : response.results().get(0).message());
            throw ApiException.conflict("Limit utilisation failed: " + reason);
        }
        d.setStatus("RELEASED");
        d.setReleasedBy(actor);
        d.setReleasedAt(Instant.now());
        d.setUtilisationRef(txnRef);
        Disbursement saved = repo.save(d);

        // If this is a syndicated deal, the agent allocates the funded draw pro-rata
        // across lenders. Best-effort + idempotent — never blocks the release.
        upstream.allocateSyndicationOrSkip(d.getApplicationReference(), txnRef, Money.asDouble(d.getAmount()),
                d.getCurrency(), actor);
        // For PF tranches, mark the milestone DRAWN so it can't be re-drawn.
        pf.markDrawn(d.getApplicationReference(), d.getFacilityRef(), d.getMilestoneSequence(), d.getId());
        audit.human(actor, "DISBURSEMENT_RELEASED", "Disbursement", String.valueOf(id),
                "Released drawdown #%d of %.2f %s on %s (utilisation %s)".formatted(
                        d.getDrawdownNo(), d.getAmount(), d.getCurrency(), d.getFacilityRef(), txnRef),
                Map.of("applicationReference", d.getApplicationReference(),
                        "facilityRef", d.getFacilityRef(),
                        "amount", d.getAmount(), "drawdownNo", d.getDrawdownNo(),
                        "utilisationRef", txnRef));
        return saved;
    }

    // ============================================================ reverse (post-RELEASED correction)

    /**
     * Reverses a RELEASED drawdown — the correction lane for money that already
     * moved. Books a {@code REVERSAL} on the limit node (undoing both outstanding
     * and cumulative drawn), reinstates a PF milestone the draw consumed, and asks
     * the syndication agent to reverse the pro-rata allocation.
     *
     * <p>SoD: the reverser must differ from the releaser — the actor whose booking
     * is being undone cannot quietly undo it. A reason is mandatory.</p>
     *
     * <p>Repayment interplay: confirmed repayments have already reduced the limit
     * ledger, so a reversal is only allowed while the facility's REMAINING released
     * draws still cover the repaid principal — otherwise the ledger would go
     * negative and the arithmetic no longer supports a full-draw reversal.</p>
     */
    @Transactional
    public Disbursement reverse(Long id, String reason, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_REVERSE);
        Disbursement d = get(id);
        if (!"RELEASED".equals(d.getStatus())) {
            throw ApiException.conflict("Only a RELEASED drawdown can be reversed — this one is " + d.getStatus());
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reversal reason is mandatory");
        }
        if (actor.equals(d.getReleasedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Reversal must be made by a different actor than the releaser (" + d.getReleasedBy() + ")");
        }
        double repaidPrincipal = repayments
                .findByApplicationReferenceAndFacilityRefAndStatusIn(
                        d.getApplicationReference(), d.getFacilityRef(), List.of("CONFIRMED"))
                .stream().map(r -> r.getPrincipalComponent()).reduce(Money.ZERO, Money::add).doubleValue();
        double releasedOther = repo
                .findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(
                        d.getApplicationReference(), d.getFacilityRef())
                .stream()
                .filter(other -> !other.getId().equals(d.getId()))
                .filter(other -> "RELEASED".equals(other.getStatus()))
                .map(Disbursement::getAmount).reduce(Money.ZERO, Money::add).doubleValue();
        if (repaidPrincipal > releasedOther + 0.01) {
            throw ApiException.conflict(("Facility %s has %.2f of confirmed principal repayments but only %.2f"
                    + " would remain released after this reversal — the ledger would go negative;"
                    + " adjust the repayments first").formatted(
                            d.getFacilityRef(), repaidPrincipal, releasedOther));
        }

        LimitClient.LimitNodeDto node = limits.nodeForFacility(d.getApplicationReference(), d.getFacilityRef());
        if (node == null) {
            throw ApiException.conflict("No limit node for facility " + d.getFacilityRef()
                    + " on " + d.getApplicationReference());
        }
        String revRef = "REV-" + d.getUtilisationRef();
        UtilisationResponseDto response = limits.reversal(node.cif(), node.reference(),
                Money.asDouble(d.getAmount()), d.getCurrency(), revRef, actor);
        if (response == null || !response.success()) {
            String cause = response == null ? "no response from limit-service"
                    : (response.results() == null || response.results().isEmpty()
                            ? "no result rows" : response.results().get(0).message());
            throw ApiException.conflict("Limit reversal failed: " + cause);
        }
        d.setStatus("REVERSED");
        d.setReversedBy(actor);
        d.setReversedReason(reason);
        d.setReversedAt(Instant.now());
        d.setReversalRef(revRef);
        Disbursement saved = repo.save(d);

        // Reinstate the PF milestone this draw consumed (no-op for non-PF).
        pf.unmarkDrawn(d.getApplicationReference(), d.getFacilityRef(), d.getMilestoneSequence(), d.getId());
        // Best-effort + idempotent — never blocks the reversal.
        upstream.reverseSyndicationOrSkip(d.getApplicationReference(), d.getUtilisationRef(), actor);

        audit.human(actor, "DISBURSEMENT_REVERSED", "Disbursement", String.valueOf(id),
                "Reversed drawdown #%d of %.2f %s on %s — %s (reversal %s)".formatted(
                        d.getDrawdownNo(), d.getAmount(), d.getCurrency(), d.getFacilityRef(), reason, revRef),
                Map.of("applicationReference", d.getApplicationReference(),
                        "facilityRef", d.getFacilityRef(), "amount", d.getAmount(),
                        "drawdownNo", d.getDrawdownNo(), "reason", reason, "reversalRef", revRef));
        return saved;
    }

    // ============================================================ reject (any state pre-RELEASED)

    @Transactional
    public Disbursement reject(Long id, String reason, String actor) {
        requireActor(actor);
        roles.require(actor, ProtectedAction.DISBURSEMENT_REJECT);
        Disbursement d = get(id);
        if ("RELEASED".equals(d.getStatus())) {
            throw ApiException.conflict("Cannot reject a RELEASED disbursement — use a reversal instead");
        }
        if ("CANCELLED".equals(d.getStatus()) || "REJECTED".equals(d.getStatus())) {
            throw ApiException.conflict("Drawdown already " + d.getStatus());
        }
        // Reject is the checker's veto lane — the requester withdraws via /cancel instead.
        if (actor.equals(d.getRequestedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "The requester (" + actor + ") cannot reject their own drawdown — use /cancel");
        }
        d.setStatus("REJECTED");
        d.setRejectedBy(actor);
        d.setRejectedReason(reason);
        d.setRejectedAt(Instant.now());
        Disbursement saved = repo.save(d);
        audit.human(actor, "DISBURSEMENT_REJECTED", "Disbursement", String.valueOf(id),
                "Rejected drawdown #%d on %s — %s".formatted(
                        d.getDrawdownNo(), d.getFacilityRef(), reason),
                Map.of("applicationReference", d.getApplicationReference(),
                        "facilityRef", d.getFacilityRef(),
                        "reason", reason));
        return saved;
    }

    // ============================================================ read

    @Transactional(readOnly = true)
    public Disbursement get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No disbursement: " + id));
    }

    @Transactional(readOnly = true)
    public List<Disbursement> historyFor(String applicationReference) {
        return repo.findByApplicationReferenceOrderByIdDesc(applicationReference);
    }

    @Transactional(readOnly = true)
    public List<Disbursement> historyForFacility(String applicationReference, String facilityRef) {
        return repo.findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(applicationReference, facilityRef);
    }

    private FacilityViewDto findFacility(DealEnvelopeDto env, String facilityRef) {
        if (env.facilities() == null) return null;
        for (FacilityViewDto f : env.facilities()) {
            if (f.reference() != null && f.reference().equals(facilityRef)) return f;
        }
        return null;
    }
}
