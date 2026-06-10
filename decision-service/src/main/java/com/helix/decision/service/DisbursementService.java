package com.helix.decision.service;

import com.helix.common.audit.AuditService;
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
 * <p>SoD: requester ≠ authoriser ≠ releaser, each transition stamped against a
 * named actor on the audit trail. {@link ApiException#forbiddenAutonomy} fires
 * the moment a maker tries to be the checker, matching the platform convention.</p>
 */
@Service
public class DisbursementService {

    private final DisbursementRepository repo;
    private final ConditionPrecedentService cps;
    private final PfService pf;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final AuditService audit;

    public DisbursementService(DisbursementRepository repo, ConditionPrecedentService cps, PfService pf,
                               UpstreamClient upstream, LimitClient limits, AuditService audit) {
        this.repo = repo;
        this.cps = cps;
        this.pf = pf;
        this.upstream = upstream;
        this.limits = limits;
        this.audit = audit;
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
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, facilityRef);
        if (facility == null) {
            throw ApiException.badRequest("No facility '" + facilityRef + "' on " + applicationReference);
        }
        if (currency == null || currency.isBlank()) currency = facility.currency();
        if (!currency.equalsIgnoreCase(facility.currency())) {
            throw ApiException.badRequest("Disbursement currency " + currency
                    + " differs from facility currency " + facility.currency()
                    + "; cross-currency disbursements need an FX conversion step");
        }
        double used = repo.findByApplicationReferenceAndFacilityRefOrderByDrawdownNoAsc(applicationReference, facilityRef)
                .stream()
                .filter(d -> !"REJECTED".equals(d.getStatus()))
                .mapToDouble(Disbursement::getAmount)
                .sum();
        if (used + amount > facility.amount() + 0.01) {
            throw ApiException.badRequest("Drawdown of " + amount + " would exceed facility "
                    + facilityRef + " (sanctioned " + facility.amount() + ", used " + used + ")");
        }
        int nextNo = repo.findFirstByApplicationReferenceAndFacilityRefOrderByDrawdownNoDesc(applicationReference, facilityRef)
                .map(Disbursement::getDrawdownNo).orElse(0) + 1;

        Disbursement d = new Disbursement();
        d.setApplicationReference(applicationReference);
        d.setFacilityRef(facilityRef);
        d.setDrawdownNo(nextNo);
        d.setAmount(amount);
        d.setCurrency(currency.toUpperCase());
        d.setBaseAmount(amount);  // updated on release
        d.setPurpose(purpose);
        d.setNarrative(narrative);
        d.setStatus("DRAFT");
        d.setRequestedBy(actor);
        d.setMilestoneSequence(milestoneSequence);
        Disbursement saved = repo.save(d);
        audit.human(actor, "DISBURSEMENT_REQUESTED", "Disbursement", String.valueOf(saved.getId()),
                "Drawdown #%d of %.2f %s requested on %s".formatted(nextNo, amount, currency, facilityRef),
                Map.of("applicationReference", applicationReference, "facilityRef", facilityRef,
                        "amount", amount, "currency", currency, "drawdownNo", nextNo));
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
        Disbursement d = get(id);
        if (!"DRAFT".equals(d.getStatus())) {
            throw ApiException.conflict("Disbursement is " + d.getStatus());
        }
        if (actor != null && actor.equals(d.getRequestedBy())) {
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
     * the facility's limit node and marks the disbursement RELEASED. SoD:
     * releaser ≠ authoriser. If the limit booking fails we leave the disbursement
     * AUTHORIZED (the underlying transactional boundary doesn't extend into the
     * remote service, but the limit-service call is idempotent on transactionRef).
     */
    @Transactional
    public Disbursement release(Long id, String actor) {
        Disbursement d = get(id);
        if (!"AUTHORIZED".equals(d.getStatus())) {
            throw ApiException.conflict("Disbursement is " + d.getStatus() + " — authorise first");
        }
        if (actor != null && actor.equals(d.getAuthorizedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Drawdown releaser must differ from authoriser (" + actor + ")");
        }
        LimitClient.LimitNodeDto node = limits.nodeForFacility(d.getApplicationReference(), d.getFacilityRef());
        if (node == null) {
            throw ApiException.conflict("No limit node for facility " + d.getFacilityRef()
                    + " on " + d.getApplicationReference() + " — build the limit tree first");
        }
        String txnRef = "DISB-" + d.getApplicationReference() + "-" + d.getFacilityRef() + "-" + d.getDrawdownNo();
        UtilisationResponseDto response = limits.utilise(node.cif(), node.reference(),
                d.getAmount(), d.getCurrency(), txnRef, actor);
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
        upstream.allocateSyndicationOrSkip(d.getApplicationReference(), txnRef, d.getAmount(),
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

    // ============================================================ reject (any state pre-RELEASED)

    @Transactional
    public Disbursement reject(Long id, String reason, String actor) {
        Disbursement d = get(id);
        if ("RELEASED".equals(d.getStatus())) {
            throw ApiException.conflict("Cannot reject a RELEASED disbursement — use a reversal instead");
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
