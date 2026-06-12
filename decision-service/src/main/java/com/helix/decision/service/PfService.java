package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.decision.dto.PfDtos.PfBlocker;
import com.helix.decision.dto.PfDtos.PfGateResult;
import com.helix.decision.entity.PfMilestone;
import com.helix.decision.entity.PfReserveAccount;
import com.helix.decision.repo.PfMilestoneRepository;
import com.helix.decision.repo.PfReserveAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Project-finance post-drawdown mechanics:
 *
 * <ul>
 *   <li><b>Construction milestones</b> with a Lender's-Independent-Engineer (LIE)
 *       certification gate — each tranche draws against a milestone that must be
 *       LIE_CERTIFIED first.</li>
 *   <li><b>Reserve accounts</b> (DSRA / TRA) tracked against their required minimum;
 *       a shortfall blocks drawdowns.</li>
 * </ul>
 *
 * <p>{@link #assertDrawable} is the PF drawdown gate, layered on top of the CP gate
 * by {@link DisbursementService}. It is a no-op for non-PF facilities (those with
 * no milestones defined), so it composes cleanly with the existing flow.</p>
 *
 * <p>SoD: the LIE certifier is a named human, distinct from the drawdown
 * requester/authoriser — enforced in {@link DisbursementService} where the
 * drawdown SoD lives.</p>
 */
@Service
public class PfService {

    private final PfMilestoneRepository milestones;
    private final PfReserveAccountRepository reserves;
    private final AuditService audit;

    public PfService(PfMilestoneRepository milestones, PfReserveAccountRepository reserves, AuditService audit) {
        this.milestones = milestones;
        this.reserves = reserves;
        this.audit = audit;
    }

    // ============================================================ milestones

    @Transactional
    public PfMilestone defineMilestone(String ref, String facilityRef, int sequence, String name,
                                       double plannedAmount, String currency,
                                       java.time.LocalDate plannedDate, String actor) {
        milestones.findByApplicationReferenceAndFacilityRefAndSequence(ref, facilityRef, sequence)
                .ifPresent(m -> { throw ApiException.conflict("Milestone seq " + sequence
                        + " already exists for " + facilityRef); });
        PfMilestone m = new PfMilestone();
        m.setApplicationReference(ref);
        m.setFacilityRef(facilityRef);
        m.setSequence(sequence);
        m.setName(name);
        m.setPlannedAmount(plannedAmount);
        m.setCurrency(currency == null || currency.isBlank() ? "INR" : currency.toUpperCase());
        m.setPlannedDate(plannedDate);
        PfMilestone saved = milestones.save(m);
        audit.human(actor, "PF_MILESTONE_DEFINED", "PfMilestone", String.valueOf(saved.getId()),
                "Defined milestone #%d '%s' (%.2f %s) on %s".formatted(sequence, name, plannedAmount,
                        saved.getCurrency(), facilityRef),
                Map.of("facilityRef", facilityRef, "sequence", sequence, "plannedAmount", plannedAmount));
        return saved;
    }

    @Transactional
    public PfMilestone lieCertify(Long id, String certificationRef, String note, String actor) {
        PfMilestone m = getMilestone(id);
        if (!"PLANNED".equals(m.getStatus())) {
            throw ApiException.conflict("Milestone is " + m.getStatus() + " — only PLANNED can be certified");
        }
        m.setStatus("LIE_CERTIFIED");
        m.setLieCertifiedBy(actor);
        m.setLieCertifiedAt(Instant.now());
        m.setCertificationRef(certificationRef);
        PfMilestone saved = milestones.save(m);
        audit.human(actor, "PF_MILESTONE_LIE_CERTIFIED", "PfMilestone", String.valueOf(id),
                "LIE-certified milestone #%d on %s (%s)%s".formatted(m.getSequence(), m.getFacilityRef(),
                        certificationRef, note == null ? "" : " — " + note),
                Map.of("facilityRef", m.getFacilityRef(), "sequence", m.getSequence(),
                        "certificationRef", certificationRef));
        return saved;
    }

    /** Marks the milestone DRAWN once the tranche releases. Called from DisbursementService. */
    @Transactional
    public void markDrawn(String ref, String facilityRef, Integer sequence, Long disbursementId) {
        if (sequence == null) return;
        milestones.findByApplicationReferenceAndFacilityRefAndSequence(ref, facilityRef, sequence)
                .ifPresent(m -> {
                    m.setStatus("DRAWN");
                    m.setDrawnByDisbursementId(disbursementId);
                    milestones.save(m);
                    audit.engine("PF_MILESTONE_DRAWN", "PfMilestone", String.valueOf(m.getId()),
                            "Milestone #%d on %s drawn by disbursement %d".formatted(
                                    sequence, facilityRef, disbursementId),
                            Map.of("facilityRef", facilityRef, "sequence", sequence,
                                    "disbursementId", disbursementId));
                });
    }

    @Transactional(readOnly = true)
    public List<PfMilestone> milestonesFor(String ref, String facilityRef) {
        return facilityRef == null || facilityRef.isBlank()
                ? milestones.findByApplicationReferenceOrderBySequenceAsc(ref)
                : milestones.findByApplicationReferenceAndFacilityRefOrderBySequenceAsc(ref, facilityRef);
    }

    // ============================================================ reserves

    @Transactional
    public PfReserveAccount defineReserve(String ref, String type, double required, String currency, String actor) {
        PfReserveAccount r = new PfReserveAccount();
        r.setApplicationReference(ref);
        r.setAccountType(type.toUpperCase());
        r.setRequiredAmount(required);
        r.setCurrentBalance(0.0);
        r.setCurrency(currency == null || currency.isBlank() ? "INR" : currency.toUpperCase());
        r.setStatus("SHORTFALL");
        PfReserveAccount saved = reserves.save(r);
        audit.human(actor, "PF_RESERVE_DEFINED", "PfReserveAccount", String.valueOf(saved.getId()),
                "Defined %s reserve, required %.2f %s on %s".formatted(r.getAccountType(), required,
                        r.getCurrency(), ref),
                Map.of("accountType", r.getAccountType(), "required", required));
        return saved;
    }

    @Transactional
    public PfReserveAccount fund(Long id, double amount, String note, String actor) {
        PfReserveAccount r = getReserve(id);
        r.setCurrentBalance(round2(r.getCurrentBalance() + amount));
        r.setStatus(r.getCurrentBalance() + 1e-6 >= r.getRequiredAmount() ? "FUNDED" : "SHORTFALL");
        r.setLastActionBy(actor);
        r.setLastActionAt(Instant.now());
        r.setLastFundedBy(actor);
        PfReserveAccount saved = reserves.save(r);
        audit.human(actor, "PF_RESERVE_FUNDED", "PfReserveAccount", String.valueOf(id),
                "Funded %s by %.2f -> balance %.2f (%s)".formatted(r.getAccountType(), amount,
                        r.getCurrentBalance(), r.getStatus()),
                Map.of("accountType", r.getAccountType(), "amount", amount, "balance", r.getCurrentBalance()));
        return saved;
    }

    /**
     * Withdraws from a reserve. SoD: the withdrawer must differ from whoever last
     * funded the account — otherwise one treasury actor could overfund, watch the
     * gate open, and quietly pull the excess back out.
     */
    @Transactional
    public PfReserveAccount withdraw(Long id, double amount, String note, String actor) {
        PfReserveAccount r = getReserve(id);
        if (amount > r.getCurrentBalance() + 1e-6) {
            throw ApiException.badRequest("Withdrawal " + amount + " exceeds balance " + r.getCurrentBalance());
        }
        if (actor != null && actor.equals(r.getLastFundedBy())) {
            throw ApiException.forbiddenAutonomy(
                    "Reserve withdrawal must be made by a different actor than the last funder ("
                    + r.getLastFundedBy() + ")");
        }
        r.setCurrentBalance(round2(r.getCurrentBalance() - amount));
        r.setStatus(r.getCurrentBalance() + 1e-6 >= r.getRequiredAmount() ? "FUNDED" : "SHORTFALL");
        r.setLastActionBy(actor);
        r.setLastActionAt(Instant.now());
        PfReserveAccount saved = reserves.save(r);
        audit.human(actor, "PF_RESERVE_WITHDRAWN", "PfReserveAccount", String.valueOf(id),
                "Withdrew %.2f from %s -> balance %.2f (%s)".formatted(amount, r.getAccountType(),
                        r.getCurrentBalance(), r.getStatus()),
                Map.of("accountType", r.getAccountType(), "amount", amount, "balance", r.getCurrentBalance()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<PfReserveAccount> reservesFor(String ref) {
        return reserves.findByApplicationReferenceOrderByIdAsc(ref);
    }

    // ============================================================ the PF drawdown gate

    /**
     * Returns the PF gate state for a facility's drawdown against a given milestone.
     * No milestones defined ⇒ not a (gated) PF facility ⇒ canDrawdown=true.
     */
    @Transactional(readOnly = true)
    public PfGateResult gate(String ref, String facilityRef, Integer milestoneSequence) {
        List<PfMilestone> ms = milestones.findByApplicationReferenceAndFacilityRefOrderBySequenceAsc(ref, facilityRef);
        if (ms.isEmpty()) {
            return new PfGateResult(facilityRef, milestoneSequence, true, List.of());
        }
        List<PfBlocker> blockers = new ArrayList<>();
        if (milestoneSequence == null) {
            blockers.add(new PfBlocker("MILESTONE", "NO_MILESTONE",
                    "PF facility " + facilityRef + " requires the drawdown to name a milestone sequence"));
        } else {
            PfMilestone m = ms.stream().filter(x -> x.getSequence() == milestoneSequence).findFirst().orElse(null);
            if (m == null) {
                blockers.add(new PfBlocker("MILESTONE", "UNKNOWN_MILESTONE",
                        "No milestone #" + milestoneSequence + " on " + facilityRef));
            } else if ("DRAWN".equals(m.getStatus())) {
                blockers.add(new PfBlocker("MILESTONE", "ALREADY_DRAWN",
                        "Milestone #" + milestoneSequence + " has already been drawn"));
            } else if (!"LIE_CERTIFIED".equals(m.getStatus())) {
                blockers.add(new PfBlocker("MILESTONE", "NOT_CERTIFIED",
                        "Milestone #" + milestoneSequence + " '" + m.getName()
                        + "' is " + m.getStatus() + " — needs LIE certification"));
            }
        }
        for (PfReserveAccount r : reserves.findByApplicationReferenceOrderByIdAsc(ref)) {
            if (r.getCurrentBalance() + 1e-6 < r.getRequiredAmount()) {
                blockers.add(new PfBlocker("RESERVE", r.getAccountType(),
                        "%s underfunded: %.2f / %.2f %s".formatted(r.getAccountType(),
                                r.getCurrentBalance(), r.getRequiredAmount(), r.getCurrency())));
            }
        }
        return new PfGateResult(facilityRef, milestoneSequence, blockers.isEmpty(), blockers);
    }

    /** Hard gate used by the disbursement authorise path. Throws 403 with blockers. */
    @Transactional(readOnly = true)
    public void assertDrawable(String ref, String facilityRef, Integer milestoneSequence) {
        PfGateResult g = gate(ref, facilityRef, milestoneSequence);
        if (!g.canDrawdown()) {
            String summary = g.blockers().stream()
                    .map(b -> b.code() + ": " + b.detail())
                    .reduce((a, b) -> a + "; " + b).orElse("PF gate blocked");
            throw ApiException.forbiddenAutonomy("PF drawdown gate blocked on " + facilityRef + " — " + summary);
        }
    }

    // ============================================================ helpers

    private PfMilestone getMilestone(Long id) {
        return milestones.findById(id).orElseThrow(() -> ApiException.notFound("No milestone: " + id));
    }

    private PfReserveAccount getReserve(Long id) {
        return reserves.findById(id).orElseThrow(() -> ApiException.notFound("No reserve account: " + id));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
