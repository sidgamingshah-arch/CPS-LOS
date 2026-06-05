package com.helix.portfolio.service;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import com.helix.portfolio.entity.CorrectiveAction;
import com.helix.portfolio.repo.CorrectiveActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Corrective Action Plan workflow (PRD CAP module). Credit team raises an action
 * against a borrower (optionally linked to an EWS signal). The owner submits a
 * response; the credit team closes it (with SoD — closer ≠ owner). Overdue and
 * escalation states are derived from target date + escalation days.
 */
@Service
public class CorrectiveActionService {

    private final CorrectiveActionRepository capRepo;
    private final AuditService audit;

    public CorrectiveActionService(CorrectiveActionRepository capRepo, AuditService audit) {
        this.capRepo = capRepo;
        this.audit = audit;
    }

    @Transactional
    public CorrectiveAction raise(String applicationReference, Long signalId, String description,
                                  String criticality, LocalDate targetDate, String owner,
                                  Integer reminderDays, Integer escalationDays, String actor) {
        if (targetDate == null || targetDate.isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Target date must be today or later");
        }
        CorrectiveAction c = new CorrectiveAction();
        c.setApplicationReference(applicationReference);
        c.setSignalId(signalId);
        c.setDescription(description);
        c.setCriticality(criticality == null ? "MEDIUM" : criticality.toUpperCase());
        c.setTargetDate(targetDate);
        c.setOwner(owner);
        c.setRaisedBy(actor);
        if (reminderDays != null) c.setReminderDaysBefore(reminderDays);
        if (escalationDays != null) c.setEscalationDaysAfter(escalationDays);
        CorrectiveAction saved = capRepo.save(c);
        audit.human(actor, "CAP_RAISED", "Application", applicationReference,
                "CAP (%s) raised: %s — owner %s, due %s".formatted(c.getCriticality(), description, owner, targetDate),
                Map.of("criticality", c.getCriticality(), "owner", owner));
        return saved;
    }

    @Transactional
    public CorrectiveAction respond(Long id, String response, String docRef, String actor) {
        CorrectiveAction c = get(id);
        if (!c.getOwner().equalsIgnoreCase(actor)) {
            throw ApiException.forbiddenAutonomy("Only the assigned owner (%s) can respond".formatted(c.getOwner()));
        }
        if (!"OPEN".equals(c.getStatus()) && !"OVERDUE".equals(c.getStatus()) && !"IN_PROGRESS".equals(c.getStatus())) {
            throw ApiException.conflict("CAP is " + c.getStatus());
        }
        c.setResponse(response);
        c.setResponseDocRef(docRef);
        c.setRespondedBy(actor);
        c.setRespondedAt(Instant.now());
        c.setStatus("IN_PROGRESS");
        audit.human(actor, "CAP_RESPONDED", "CorrectiveAction", String.valueOf(id),
                "Owner submitted response", Map.of("docRef", String.valueOf(docRef)));
        return capRepo.save(c);
    }

    @Transactional
    public CorrectiveAction close(Long id, String comment, String actor) {
        CorrectiveAction c = get(id);
        if (actor.equalsIgnoreCase(c.getOwner())) {
            throw ApiException.forbiddenAutonomy("Closer must differ from the owner (segregation of duties)");
        }
        if ("COMPLETED".equals(c.getStatus())) {
            throw ApiException.conflict("CAP already closed");
        }
        c.setStatus("COMPLETED");
        c.setClosedBy(actor);
        c.setClosedAt(Instant.now());
        c.setClosureComment(comment);
        audit.human(actor, "CAP_CLOSED", "CorrectiveAction", String.valueOf(id),
                "CAP closed: " + comment, Map.of());
        return capRepo.save(c);
    }

    @Transactional
    public CorrectiveAction escalate(Long id, String reason, String actor) {
        CorrectiveAction c = get(id);
        c.setStatus("ESCALATED");
        audit.human(actor, "CAP_ESCALATED", "CorrectiveAction", String.valueOf(id),
                "Escalated: " + reason, Map.of("reason", String.valueOf(reason)));
        return capRepo.save(c);
    }

    /** Sweep open/in-progress CAPs and tip them to OVERDUE/ESCALATED based on the date math. */
    @Transactional
    public Map<String, Object> sweep(String actor) {
        LocalDate today = LocalDate.now();
        List<CorrectiveAction> open = capRepo.findByStatusInAndTargetDateBefore(
                List.of("OPEN", "IN_PROGRESS"), today.plusDays(1));
        int overdue = 0, escalated = 0;
        for (CorrectiveAction c : open) {
            if (today.isAfter(c.getTargetDate().plusDays(c.getEscalationDaysAfter()))) {
                c.setStatus("ESCALATED");
                escalated++;
                audit.engine("CAP_AUTO_ESCALATED", "CorrectiveAction", String.valueOf(c.getId()),
                        "Auto-escalated past target+%d days".formatted(c.getEscalationDaysAfter()),
                        Map.of("daysOverdue", today.toEpochDay() - c.getTargetDate().toEpochDay()));
            } else if (today.isAfter(c.getTargetDate())) {
                c.setStatus("OVERDUE");
                overdue++;
            }
            capRepo.save(c);
        }
        audit.engine("CAP_SWEEP", "CorrectiveAction", "batch",
                "Sweep: %d overdue, %d escalated".formatted(overdue, escalated),
                Map.of("overdue", overdue, "escalated", escalated));
        return Map.of("overdue", overdue, "escalated", escalated, "scanned", open.size());
    }

    @Transactional(readOnly = true)
    public List<CorrectiveAction> forApplication(String reference) {
        return capRepo.findByApplicationReferenceOrderByCreatedAtDesc(reference);
    }

    @Transactional(readOnly = true)
    public List<CorrectiveAction> inboxFor(String owner) {
        return capRepo.findByOwnerOrderByTargetDateAsc(owner);
    }

    @Transactional(readOnly = true)
    public List<CorrectiveAction> byStatus(String status) {
        return capRepo.findByStatusOrderByTargetDateAsc(status.toUpperCase());
    }

    @Transactional(readOnly = true)
    public CorrectiveAction get(Long id) {
        return capRepo.findById(id).orElseThrow(() -> ApiException.notFound("No CAP: " + id));
    }
}
