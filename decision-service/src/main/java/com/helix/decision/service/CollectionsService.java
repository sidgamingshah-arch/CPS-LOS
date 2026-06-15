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
import com.helix.decision.entity.CollectionsCase;
import com.helix.decision.entity.FacilityAmendment;
import com.helix.decision.repo.CollectionsCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collections / NPA workflow on top of the existing repayment ledger. The case
 * stages itself (STAGE_1/2/3) from DPD on every update; outcomes fan out into
 * RESTRUCTURED (via the existing FacilityAmendment lane — same DoA matrix),
 * WRITTEN_OFF (DoA-routed sub-workflow on the write-off amount + current grade),
 * LEGAL (operational, no money movement) or CURED (DPD back to zero).
 *
 * <p>RBAC: COLLECTIONS_OPS can open, update, cure, and propose write-off /
 * restructure. LEGAL initiations need COLLECTIONS_HEAD or LEGAL. Write-off
 * approval is rank-checked against the routed DoA authority, mirroring the
 * facility-amendment pattern.</p>
 */
@Service
public class CollectionsService {

    private static final int STAGE_2_DPD = 30;
    private static final int STAGE_3_DPD = 90;

    private final CollectionsCaseRepository repo;
    private final FacilityAmendmentService amendments;
    private final RepaymentService repayments;
    private final DoaRouter doaRouter;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final ActorDirectory roles;
    private final AuditService audit;

    public CollectionsService(CollectionsCaseRepository repo, FacilityAmendmentService amendments,
                              RepaymentService repayments, DoaRouter doaRouter,
                              UpstreamClient upstream, LimitClient limits,
                              ActorDirectory roles, AuditService audit) {
        this.repo = repo;
        this.amendments = amendments;
        this.repayments = repayments;
        this.doaRouter = doaRouter;
        this.upstream = upstream;
        this.limits = limits;
        this.roles = roles;
        this.audit = audit;
    }

    // ============================================================ open / update / cure

    @Transactional
    public CollectionsCase open(String applicationReference, String facilityRef, int dpd,
                                double overdueAmount, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_OPEN);
        if (dpd < 0) throw ApiException.badRequest("daysPastDue cannot be negative");
        if (overdueAmount <= 0) throw ApiException.badRequest("overdueAmount must be positive");
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null) throw ApiException.notFound("No deal envelope for " + applicationReference);
        FacilityViewDto facility = findFacility(env, facilityRef);
        if (facility == null) {
            throw ApiException.badRequest("No facility '" + facilityRef + "' on " + applicationReference);
        }
        // Only an active in-flight case (OPEN, LEGAL) blocks a fresh open — a
        // RESTRUCTURED case is historical and a borrower can default again on the
        // restructured terms; CURED/WRITTEN_OFF/CLOSED are terminal.
        var existing = repo.findFirstByApplicationReferenceAndFacilityRefAndStatusIn(
                applicationReference, facilityRef,
                List.of("OPEN", "LEGAL"));
        if (existing.isPresent()) {
            throw ApiException.conflict("An active collections case already exists for "
                    + facilityRef + " (case " + existing.get().getId() + ")");
        }

        CollectionsCase c = new CollectionsCase();
        c.setApplicationReference(applicationReference);
        c.setFacilityRef(facilityRef);
        c.setCounterpartyName(env.counterpartyName());
        c.setDaysPastDue(dpd);
        c.setOverdueAmount(Money.of(overdueAmount));
        c.setOutstandingAtOpen(Money.of(repayments.outstandingPrincipal(applicationReference, facilityRef)));
        c.setCurrency(facility.currency() == null ? "INR" : facility.currency().toUpperCase());
        c.setNpaStage(stageFor(dpd));
        c.setOpenedBy(actor);
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_CASE_OPENED", "CollectionsCase", String.valueOf(saved.getId()),
                "Opened case on %s — %ddpd, overdue %.2f %s, stage %s".formatted(
                        facilityRef, dpd, overdueAmount, c.getCurrency(), c.getNpaStage()),
                Map.of("facilityRef", facilityRef, "dpd", dpd, "stage", c.getNpaStage(),
                        "overdueAmount", overdueAmount));
        return saved;
    }

    /**
     * SYSTEM-initiated open from the monitoring sweep (portfolio EWS escalation). Unlike
     * the manual {@link #open}, this is not role-gated (automation surfacing a case, like
     * the EWS scan raising signals — every consequential action INSIDE the case is still
     * human + DoA gated), permits a zero overdue (a covenant breach is not a payment
     * default), and is idempotent: an active case for the facility is refreshed (DPD /
     * stage bumped if the signal is worse) and returned rather than duplicated.
     *
     * <p>The case is opened against the deal's primary facility — a covenant / conduct
     * signal is obligor-level, so one case per obligor is the right granularity.</p>
     */
    @Transactional
    public CollectionsCase openFromMonitoring(String applicationReference, int dpd,
                                              double overdueAmount, String trigger) {
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        if (env == null || env.facilities() == null || env.facilities().isEmpty()) {
            throw ApiException.notFound("No deal envelope/facility for " + applicationReference);
        }
        String facilityRef = env.facilities().get(0).reference();
        var existing = repo.findFirstByApplicationReferenceAndFacilityRefAndStatusIn(
                applicationReference, facilityRef, List.of("OPEN", "LEGAL"));
        if (existing.isPresent()) {
            CollectionsCase c = existing.get();
            boolean changed = false;
            if (dpd > c.getDaysPastDue()) {
                c.setDaysPastDue(dpd);
                c.setNpaStage(stageFor(dpd));
                changed = true;
            }
            if (Money.of(overdueAmount).compareTo(c.getOverdueAmount()) > 0) {
                c.setOverdueAmount(Money.of(overdueAmount));
                changed = true;
            }
            if (changed) {
                repo.save(c);
                audit.engine("COLLECTIONS_CASE_REFRESHED", "CollectionsCase", String.valueOf(c.getId()),
                        "Monitoring refreshed case on %s — %ddpd, stage %s (%s)".formatted(
                                facilityRef, c.getDaysPastDue(), c.getNpaStage(), trigger),
                        Map.of("facilityRef", facilityRef, "dpd", c.getDaysPastDue(),
                                "stage", c.getNpaStage(), "trigger", trigger == null ? "" : trigger));
            }
            return c;
        }
        FacilityViewDto facility = findFacility(env, facilityRef);
        CollectionsCase c = new CollectionsCase();
        c.setApplicationReference(applicationReference);
        c.setFacilityRef(facilityRef);
        c.setCounterpartyName(env.counterpartyName());
        c.setDaysPastDue(Math.max(0, dpd));
        c.setOverdueAmount(Money.of(Math.max(0, overdueAmount)));
        c.setOutstandingAtOpen(Money.of(repayments.outstandingPrincipal(applicationReference, facilityRef)));
        c.setCurrency(facility != null && facility.currency() != null ? facility.currency().toUpperCase() : "INR");
        c.setNpaStage(stageFor(Math.max(0, dpd)));
        c.setOpenedBy("SYSTEM:monitoring");
        c.setTriggerReason(trigger);
        CollectionsCase saved = repo.save(c);
        audit.engine("COLLECTIONS_CASE_AUTO_OPENED", "CollectionsCase", String.valueOf(saved.getId()),
                "Monitoring auto-opened case on %s — %ddpd, stage %s; trigger: %s".formatted(
                        facilityRef, c.getDaysPastDue(), c.getNpaStage(), trigger),
                Map.of("applicationReference", applicationReference, "facilityRef", facilityRef,
                        "dpd", c.getDaysPastDue(), "stage", c.getNpaStage(),
                        "trigger", trigger == null ? "" : trigger));
        return saved;
    }

    @Transactional
    public CollectionsCase updateDpd(Long id, int dpd, double overdueAmount, String note, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_UPDATE);
        CollectionsCase c = get(id);
        if (!"OPEN".equals(c.getStatus()) && !"LEGAL".equals(c.getStatus())) {
            throw ApiException.conflict("Case is " + c.getStatus());
        }
        String oldStage = c.getNpaStage();
        c.setDaysPastDue(dpd);
        c.setOverdueAmount(Money.of(overdueAmount));
        c.setNpaStage(stageFor(dpd));
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_DPD_UPDATED", "CollectionsCase", String.valueOf(id),
                "%s%s — %ddpd, overdue %.2f %s%s".formatted(
                        oldStage.equals(c.getNpaStage()) ? "DPD updated" : "Stage moved " + oldStage + " -> " + c.getNpaStage(),
                        note == null ? "" : " (" + note + ")",
                        dpd, overdueAmount, c.getCurrency(),
                        oldStage.equals(c.getNpaStage()) ? "" : " [stage transition]"),
                Map.of("dpd", dpd, "previousStage", oldStage, "newStage", c.getNpaStage()));
        return saved;
    }

    @Transactional
    public CollectionsCase assignTo(Long id, String assignee, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_UPDATE);
        CollectionsCase c = get(id);
        c.setAssignedTo(assignee);
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_ASSIGNED", "CollectionsCase", String.valueOf(id),
                "Assigned case on %s to %s".formatted(c.getFacilityRef(), assignee),
                Map.of("facilityRef", c.getFacilityRef(), "assignee", assignee));
        return saved;
    }

    @Transactional
    public CollectionsCase cure(Long id, String note, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_CURE);
        CollectionsCase c = get(id);
        if (!"OPEN".equals(c.getStatus())) {
            throw ApiException.conflict("Only OPEN cases can be cured — this one is " + c.getStatus());
        }
        if (c.getDaysPastDue() > 0 || Money.asDouble(c.getOverdueAmount()) > 0.01) {
            throw ApiException.conflict(
                    "Cannot cure while overdue stands — dpd %d, overdue %.2f. Update DPD to 0 first".formatted(
                            c.getDaysPastDue(), c.getOverdueAmount()));
        }
        c.setStatus("CURED");
        c.setNpaStage("STAGE_1");
        c.setClosedBy(actor);
        c.setClosedAt(Instant.now());
        c.setClosureNote(note);
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_CURED", "CollectionsCase", String.valueOf(id),
                "Cured case on %s%s".formatted(c.getFacilityRef(), note == null ? "" : " — " + note),
                Map.of("facilityRef", c.getFacilityRef()));
        return saved;
    }

    // ============================================================ restructure (chains FacilityAmendment)

    /**
     * Proposes a restructure via the existing FacilityAmendment lane — the same DoA
     * matrix that sanctioned the deal decides who can re-cut terms. The case keeps
     * a pointer to the amendment; on amendment APPROVAL it should be marked
     * RESTRUCTURED via {@link #markRestructured}.
     */
    @Transactional
    public FacilityAmendment proposeRestructure(Long id, Double newAmount, Integer newTenorMonths,
                                                String reason, String actor) {
        CollectionsCase c = get(id);
        if (!"OPEN".equals(c.getStatus())) {
            throw ApiException.conflict("Case is " + c.getStatus() + " — restructure needs an OPEN case");
        }
        // Restructure flows through the amendment lane via the privileged internal
        // entry point: this method already required COLLECTIONS_UPDATE on the actor,
        // and the amendment's DoA approval signature is unchanged.
        roles.require(actor, ProtectedAction.COLLECTIONS_UPDATE);
        FacilityAmendment a = amendments.proposeInternal(c.getApplicationReference(), c.getFacilityRef(),
                newAmount, newTenorMonths, "[collections-case " + id + "] " + reason, actor);
        c.setRestructureAmendmentId(a.getId());
        repo.save(c);
        audit.human(actor, "COLLECTIONS_RESTRUCTURE_PROPOSED", "CollectionsCase", String.valueOf(id),
                "Restructure proposed via amendment %d on %s — needs %s to approve".formatted(
                        a.getId(), c.getFacilityRef(), a.getRequiredAuthority()),
                Map.of("facilityRef", c.getFacilityRef(), "amendmentId", a.getId(),
                        "requiredAuthority", a.getRequiredAuthority()));
        return a;
    }

    /**
     * Flips the case to RESTRUCTURED — call this once the linked amendment is
     * APPROVED. The case stays open enough to keep tracking the restructured
     * facility (the amendment changed its terms; the collections workflow tracks
     * conduct from here).
     */
    @Transactional
    public CollectionsCase markRestructured(Long id, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_UPDATE);
        CollectionsCase c = get(id);
        if (c.getRestructureAmendmentId() == null) {
            throw ApiException.conflict("No restructure amendment linked to case " + id);
        }
        if (!"OPEN".equals(c.getStatus())) {
            throw ApiException.conflict("Case is " + c.getStatus());
        }
        c.setStatus("RESTRUCTURED");
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_RESTRUCTURED", "CollectionsCase", String.valueOf(id),
                "Case on %s marked RESTRUCTURED (amendment %d applied)".formatted(
                        c.getFacilityRef(), c.getRestructureAmendmentId()),
                Map.of("facilityRef", c.getFacilityRef(),
                        "amendmentId", c.getRestructureAmendmentId()));
        return saved;
    }

    // ============================================================ legal

    @Transactional
    public CollectionsCase initiateLegal(Long id, String legalRef, String note, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_LEGAL);
        CollectionsCase c = get(id);
        if (!"OPEN".equals(c.getStatus())) {
            throw ApiException.conflict("Case is " + c.getStatus());
        }
        if ("STAGE_1".equals(c.getNpaStage())) {
            throw ApiException.conflict("Legal proceedings are not appropriate at STAGE_1 (" + c.getDaysPastDue() + "dpd)");
        }
        if (legalRef == null || legalRef.isBlank()) {
            throw ApiException.badRequest("A legal reference (case/filing number) is required");
        }
        c.setStatus("LEGAL");
        c.setLegalRef(legalRef);
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_LEGAL_INITIATED", "CollectionsCase", String.valueOf(id),
                "Legal proceedings initiated on %s (%s)%s".formatted(c.getFacilityRef(), legalRef,
                        note == null ? "" : " — " + note),
                Map.of("facilityRef", c.getFacilityRef(), "legalRef", legalRef));
        return saved;
    }

    // ============================================================ write-off (DoA-routed)

    /**
     * Proposes a write-off — the routed authority depends on the write-off amount
     * (treated as a fresh exposure) × current grade per the DOA_MATRIX pack. The
     * approver must hold that authority's rank AND differ from the proposer; on
     * approval the limit ledger releases the written-off amount.
     */
    public record WriteOffProposal(Long caseId, double amount, String requiredAuthority,
                                   String ruleApplied) { }

    public record WriteOffDecision(CollectionsCase caseRow, double amount, String authority,
                                   String decidedBy) { }

    @Transactional
    public WriteOffProposal proposeWriteOff(Long id, double amount, String reason, String actor) {
        roles.require(actor, ProtectedAction.COLLECTIONS_WRITEOFF_PROPOSE);
        CollectionsCase c = get(id);
        if (!"OPEN".equals(c.getStatus()) && !"LEGAL".equals(c.getStatus())) {
            throw ApiException.conflict("Case is " + c.getStatus() + " — write-off needs OPEN or LEGAL");
        }
        if (amount <= 0) throw ApiException.badRequest("Write-off amount must be positive");
        double outstanding = repayments.outstandingPrincipal(c.getApplicationReference(), c.getFacilityRef());
        if (amount > outstanding + 0.01) {
            throw ApiException.badRequest(("Write-off %.2f exceeds outstanding principal %.2f on %s")
                    .formatted(amount, outstanding, c.getFacilityRef()));
        }
        RulePackDto doaPack = upstream.doaMatrix(jurisdictionFor(c.getApplicationReference()));
        RiskSummaryDto risk = upstream.riskSummaryOrNull(c.getApplicationReference());
        String grade = risk == null || risk.rating() == null ? "D" : risk.rating().finalGrade();
        DoaRouter.Routing routing = doaRouter.route(amount, grade, true, doaPack);

        // Park the proposal on the case temporarily (we don't open a parallel entity
        // — the case IS the workflow). decidedBy stays null until decideWriteOff.
        c.setWriteOffAmount(Money.of(amount));
        c.setWriteOffAuthority(routing.requiredAuthority());
        c.setWriteOffProposedBy(actor);
        repo.save(c);
        audit.human(actor, "COLLECTIONS_WRITEOFF_PROPOSED", "CollectionsCase", String.valueOf(id),
                "Write-off of %.2f %s proposed on %s — needs %s authority (%s)%s".formatted(
                        amount, c.getCurrency(), c.getFacilityRef(),
                        routing.requiredAuthority(), routing.ruleApplied(),
                        reason == null ? "" : " — " + reason),
                Map.of("facilityRef", c.getFacilityRef(), "amount", amount,
                        "requiredAuthority", routing.requiredAuthority()));
        return new WriteOffProposal(id, round2(amount), routing.requiredAuthority(), routing.ruleApplied());
    }

    @Transactional
    public CollectionsCase decideWriteOff(Long id, boolean approve, String comment, String actor) {
        CollectionsCase c = get(id);
        if (c.getWriteOffAmount() == null || c.getWriteOffAuthority() == null) {
            throw ApiException.conflict("No write-off proposed on case " + id);
        }
        if (c.getWriteOffDecidedBy() != null) {
            throw ApiException.conflict("Write-off on case " + id + " already decided");
        }
        if (actor == null || actor.isBlank() || actor.equals(c.getWriteOffProposedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Write-off decider must be a named human different from the proposer ("
                    + c.getWriteOffProposedBy() + ")");
        }
        requireAuthorityRank(actor, c.getWriteOffAuthority());

        if (!approve) {
            // Reject — clear the proposal and leave the case as-is.
            c.setWriteOffAmount(null);
            c.setWriteOffAuthority(null);
            c.setWriteOffProposedBy(null);
            CollectionsCase saved = repo.save(c);
            audit.human(actor, "COLLECTIONS_WRITEOFF_REJECTED", "CollectionsCase", String.valueOf(id),
                    "Write-off on %s rejected%s".formatted(c.getFacilityRef(),
                            comment == null ? "" : " — " + comment),
                    Map.of("facilityRef", c.getFacilityRef()));
            return saved;
        }

        // Approved — release the written-off amount on the limit ledger so exposure
        // drops, and stamp the case WRITTEN_OFF. Limit RELEASE is idempotent on the
        // transactionRef; a retry of the same decision is a no-op upstream.
        LimitClient.LimitNodeDto node = limits.nodeForFacility(c.getApplicationReference(), c.getFacilityRef());
        if (node == null) {
            throw ApiException.conflict("No limit node for facility " + c.getFacilityRef());
        }
        String txnRef = "WRITEOFF-" + c.getApplicationReference() + "-" + c.getFacilityRef() + "-" + id;
        LimitClient.UtilisationResponseDto resp = limits.release(node.cif(), node.reference(),
                Money.asDouble(c.getWriteOffAmount()), c.getCurrency(), txnRef, actor);
        if (resp == null || !resp.success()) {
            String reason = resp == null ? "no response from limit-service"
                    : (resp.results() == null || resp.results().isEmpty()
                            ? "no result rows" : resp.results().get(0).message());
            throw ApiException.conflict("Limit release for write-off failed: " + reason);
        }
        c.setStatus("WRITTEN_OFF");
        c.setWriteOffDecidedBy(actor);
        c.setClosedBy(actor);
        c.setClosedAt(Instant.now());
        c.setClosureNote(comment);
        CollectionsCase saved = repo.save(c);
        audit.human(actor, "COLLECTIONS_WRITTEN_OFF", "CollectionsCase", String.valueOf(id),
                "Wrote off %.2f %s on %s under %s authority — limit ledger released (%s)".formatted(
                        c.getWriteOffAmount(), c.getCurrency(), c.getFacilityRef(),
                        c.getWriteOffAuthority(), txnRef),
                Map.of("facilityRef", c.getFacilityRef(), "amount", c.getWriteOffAmount(),
                        "authority", c.getWriteOffAuthority(), "releaseRef", txnRef));
        return saved;
    }

    // ============================================================ reads

    @Transactional(readOnly = true)
    public CollectionsCase get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("No collections case: " + id));
    }

    @Transactional(readOnly = true)
    public List<CollectionsCase> list() {
        return repo.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<CollectionsCase> forApplication(String applicationReference) {
        return repo.findByApplicationReferenceOrderByIdDesc(applicationReference);
    }

    // ============================================================ helpers

    private String stageFor(int dpd) {
        if (dpd >= STAGE_3_DPD) return "STAGE_3";
        if (dpd >= STAGE_2_DPD) return "STAGE_2";
        return "STAGE_1";
    }

    private void requireAuthorityRank(String actor, String requiredAuthority) {
        Set<String> actorRoles = roles.rolesFor(actor);
        if (actorRoles == null) return;     // directory outage — ActorDirectory logs the WARN
        int required = Authorities.rank(requiredAuthority);
        int best = actorRoles.stream().mapToInt(Authorities::rank).max().orElse(-1);
        if (best < required) {
            throw ApiException.forbiddenAutonomy(
                    "Write-off requires " + requiredAuthority + " authority — actor '" + actor
                    + "' holds " + actorRoles + " (insufficient rank)");
        }
    }

    private FacilityViewDto findFacility(DealEnvelopeDto env, String facilityRef) {
        if (env.facilities() == null) return null;
        for (FacilityViewDto f : env.facilities()) {
            if (f.reference() != null && f.reference().equals(facilityRef)) return f;
        }
        return null;
    }

    private String jurisdictionFor(String applicationReference) {
        DealEnvelopeDto env = upstream.envelope(applicationReference);
        return env == null ? null : env.jurisdiction();
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
