package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.notify.NotificationService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.LimitClient;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CovenantAction;
import com.helix.decision.entity.CovenantSchedule;
import com.helix.decision.entity.CovenantTest;
import com.helix.decision.repo.CovenantActionRepository;
import com.helix.decision.repo.CovenantRepository;
import com.helix.decision.repo.CovenantScheduleRepository;
import com.helix.decision.repo.CovenantTestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Covenant tracking workflow (PRD Covenant Tracking module). Each covenant gets a
 * monitoring schedule (frequency, period, grace). Driving the due date forwards
 * tests the covenant against the latest spread ratios and updates the state
 * machine: SCHEDULED → COMPLIANT / BREACHED / OVERDUE → WAIVED / EXTENDED.
 * Extensions and waivers go through a maker-checker request/decision flow with SoD.
 */
@Service
public class CovenantWorkflowService {

    private static final String SCHEDULED = "SCHEDULED";
    private static final String COMPLIANT = "COMPLIANT";
    private static final String BREACHED = "BREACHED";
    private static final String OVERDUE = "OVERDUE";
    private static final String WAIVED = "WAIVED";
    private static final String EXTENDED = "EXTENDED";

    private final CovenantScheduleRepository schedules;
    private final CovenantActionRepository actions;
    private final CovenantRepository covenants;
    private final CovenantTestRepository tests;
    private final UpstreamClient upstream;
    private final LimitClient limits;
    private final AuditService audit;
    private final NotificationService notifications;

    public CovenantWorkflowService(CovenantScheduleRepository schedules, CovenantActionRepository actions,
                                   CovenantRepository covenants, CovenantTestRepository tests,
                                   UpstreamClient upstream, LimitClient limits, AuditService audit,
                                   NotificationService notifications) {
        this.schedules = schedules;
        this.actions = actions;
        this.covenants = covenants;
        this.tests = tests;
        this.upstream = upstream;
        this.limits = limits;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** Enqueue a notification without ever failing the caller's business operation. */
    private void safeNotify(NotificationService.Enqueue cmd, String actor) {
        try {
            notifications.enqueue(cmd, actor);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(CovenantWorkflowService.class)
                    .warn("notification enqueue failed for {} ({})", cmd.eventType(), e.getMessage());
        }
    }

    /** Opens (or re-opens) a schedule for every active covenant on a deal. */
    @Transactional
    public List<CovenantSchedule> initialiseSchedules(String reference, LocalDate startDate, LocalDate endDate, String actor) {
        List<Covenant> covs = covenants.findByApplicationReference(reference).stream()
                .filter(Covenant::isActive).toList();
        List<CovenantSchedule> created = new ArrayList<>();
        for (Covenant c : covs) {
            if (schedules.findFirstByCovenantIdOrderByIdDesc(c.getId()).isPresent()) {
                continue;
            }
            CovenantSchedule s = new CovenantSchedule();
            s.setCovenantId(c.getId());
            s.setApplicationReference(reference);
            s.setMetric(c.getMetric());
            s.setTestFrequency(c.getTestFrequency() == null ? "QUARTERLY" : c.getTestFrequency());
            s.setStartDate(startDate);
            s.setEndDate(endDate);
            s.setCurrentDueDate(addPeriod(startDate, s.getTestFrequency()));
            s.setGraceDays(Math.max(0, c.getCurePeriodDays()));
            s.setStatus(SCHEDULED);
            created.add(schedules.save(s));
        }
        audit.engine("COVENANT_SCHEDULES_INIT", "Application", reference,
                "Initialised %d covenant schedule(s)".formatted(created.size()),
                Map.of("created", created.size()));
        return created;
    }

    @Transactional(readOnly = true)
    public List<CovenantSchedule> list(String reference) {
        return schedules.findByApplicationReferenceOrderByCurrentDueDateAsc(reference);
    }

    /** Tests every active schedule on the deal against the latest ratios. */
    @Transactional
    public List<CovenantSchedule> runDue(String reference, String actor) {
        List<CovenantSchedule> ss = schedules.findByApplicationReferenceOrderByCurrentDueDateAsc(reference);
        var inputs = upstream.creditInputs(reference);
        Map<String, Double> ratios = inputs.ratios() == null ? Map.of() : inputs.ratios();
        LocalDate today = LocalDate.now();
        List<CovenantSchedule> updated = new ArrayList<>();
        for (CovenantSchedule s : ss) {
            if (List.of(WAIVED, EXTENDED).contains(s.getStatus()) && today.isBefore(s.getCurrentDueDate())) {
                continue;
            }
            Covenant c = covenants.findById(s.getCovenantId()).orElse(null);
            if (c == null) continue;
            double observed = ratios.getOrDefault(s.getMetric(), 0.0);
            boolean passed = satisfies(observed, c.getOperator(), c.getThreshold());
            CovenantTest t = new CovenantTest();
            t.setCovenantId(c.getId());
            t.setApplicationReference(reference);
            t.setMetric(c.getMetric());
            t.setOperator(c.getOperator());
            t.setThreshold(c.getThreshold());
            t.setObserved(observed);
            t.setPassed(passed);
            t.setSource(c.getSource() == null ? "borrower_management_accounts" : c.getSource());
            t.setBreachSeverity(c.getBreachSeverity());
            tests.save(t);
            String newStatus;
            if (passed) {
                newStatus = COMPLIANT;
            } else if (today.isAfter(s.getCurrentDueDate().plusDays(s.getGraceDays()))) {
                newStatus = OVERDUE;
            } else {
                newStatus = BREACHED;
            }
            s.setStatus(newStatus);
            s.setLastTestedAt(today);
            s.setCurrentDueDate(addPeriod(s.getCurrentDueDate(), s.getTestFrequency()));
            updated.add(schedules.save(s));
            audit.engine("COVENANT_TESTED", "Application", reference,
                    "%s %s %s vs observed %.2f -> %s".formatted(c.getMetric(), c.getOperator(),
                            c.getThreshold(), observed, newStatus),
                    Map.of("metric", c.getMetric(), "passed", passed, "status", newStatus));
            if (BREACHED.equals(newStatus) || OVERDUE.equals(newStatus)) {
                String action = c.getOnBreach() == null || c.getOnBreach().isEmpty()
                        ? "review" : String.join(", ", c.getOnBreach());
                safeNotify(new NotificationService.Enqueue("COVENANT_BREACH", "COVENANT_BREACH",
                        "Application", reference, "cov:" + c.getId() + ":test:" + today,
                        inputs.jurisdiction(), Map.of("borrower", inputs.counterpartyName(),
                        "metric", c.getMetric(), "operator", c.getOperator(), "threshold", c.getThreshold(),
                        "result", observed, "asOf", today.toString(), "action", action), null), actor);
            }
        }
        return updated;
    }

    /** Surfaces schedules whose next due date is within {@code days} days from today. */
    @Transactional(readOnly = true)
    public List<CovenantSchedule> upcoming(int days) {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(Math.max(1, days));
        return schedules.findByCurrentDueDateBetweenAndStatusNot(today, horizon, WAIVED);
    }

    /** Emits a near-due alert per schedule (template-driven, audited). */
    @Transactional
    public int sendUpcomingAlerts(int days, String actor) {
        List<CovenantSchedule> due = upcoming(days);
        for (CovenantSchedule s : due) {
            audit.engine("NOTIFICATION_SENT", "Application", s.getApplicationReference(),
                    "EMAIL_TEMPLATE COVENANT_DUE: %s due %s".formatted(s.getMetric(), s.getCurrentDueDate()),
                    Map.of("template", "COVENANT_DUE", "metric", s.getMetric(),
                            "dueDate", s.getCurrentDueDate().toString()));
            safeNotify(new NotificationService.Enqueue("COVENANT_DUE", "COVENANT_DUE",
                    "Application", s.getApplicationReference(),
                    "schedule:" + s.getId() + ":due:" + s.getCurrentDueDate(), null,
                    Map.of("borrower", s.getApplicationReference(), "metric", s.getMetric(),
                            "dueDate", s.getCurrentDueDate().toString()), null), actor);
        }
        return due.size();
    }

    // --------------------------------------------------- request / decide

    @Transactional
    public CovenantAction requestExtension(Long scheduleId, LocalDate newDueDate, String reason, String actor) {
        CovenantSchedule s = get(scheduleId);
        if (!newDueDate.isAfter(s.getCurrentDueDate())) {
            throw ApiException.badRequest("Extension must be later than current due date " + s.getCurrentDueDate());
        }
        return openAction(s, "REQUEST_EXTENSION", reason, newDueDate, actor);
    }

    @Transactional
    public CovenantAction requestWaiver(Long scheduleId, String reason, String actor) {
        return openAction(get(scheduleId), "REQUEST_WAIVER", reason, null, actor);
    }

    @Transactional
    public CovenantAction freezeAccounts(Long scheduleId, String reason, String actor) {
        return openAction(get(scheduleId), "FREEZE_ACCOUNTS", reason, null, actor);
    }

    private CovenantAction openAction(CovenantSchedule s, String action, String reason,
                                       LocalDate newDueDate, String actor) {
        CovenantAction a = new CovenantAction();
        a.setScheduleId(s.getId());
        a.setAction(action);
        a.setStatus("PENDING");
        a.setNewDueDate(newDueDate);
        a.setReason(reason);
        a.setRequestedBy(actor);
        CovenantAction saved = actions.save(a);
        audit.human(actor, "COVENANT_ACTION_REQUESTED", "CovenantSchedule", String.valueOf(s.getId()),
                "%s on %s: %s".formatted(action, s.getMetric(), reason),
                Map.of("action", action));
        return saved;
    }

    @Transactional
    public CovenantAction decide(Long actionId, boolean approve, String comment, String actor) {
        CovenantAction a = actions.findById(actionId)
                .orElseThrow(() -> ApiException.notFound("No action: " + actionId));
        if (!"PENDING".equals(a.getStatus())) {
            throw ApiException.conflict("Action already decided");
        }
        if (actor.equalsIgnoreCase(a.getRequestedBy())) {
            throw ApiException.forbiddenAutonomy("Approver cannot be the requester (segregation of duties)");
        }
        a.setDecidedBy(actor);
        a.setDecidedAt(java.time.Instant.now());
        a.setDecisionComment(comment);
        if (!approve) {
            a.setStatus("REJECTED");
            audit.human(actor, "COVENANT_ACTION_REJECTED", "CovenantAction", String.valueOf(actionId),
                    "Rejected " + a.getAction(), Map.of());
            return actions.save(a);
        }
        a.setStatus("APPROVED");
        CovenantSchedule s = get(a.getScheduleId());
        switch (a.getAction()) {
            case "REQUEST_EXTENSION" -> {
                s.setOriginalDueDate(s.getCurrentDueDate());
                s.setCurrentDueDate(a.getNewDueDate());
                s.setStatus(EXTENDED);
                schedules.save(s);
            }
            case "REQUEST_WAIVER" -> {
                s.setStatus(WAIVED);
                schedules.save(s);
            }
            case "FREEZE_ACCOUNTS", "FREEZE_DISBURSEMENT" -> {
                // REAL feed to limit-service: freeze the obligor's limit nodes for this
                // application (status -> FROZEN, honoured as a hard-stop on UTILISE/RESERVE).
                // Governance SIDE-EFFECT of the already-committed approval — a limit-service
                // outage or a not-yet-built tree must never roll back the covenant decision.
                LimitClient.AppStatusResultDto frozen = limits.freezeApplication(
                        s.getApplicationReference(), a.getAction() + ": " + a.getReason(), actor);
                if (frozen.affectedCount() < 0) {
                    audit.engine("LIMIT_FREEZE_SKIPPED", "Application", s.getApplicationReference(),
                            "limit-service unavailable; limits NOT frozen for %s (covenant %s stands, retry freeze)"
                                    .formatted(s.getApplicationReference(), a.getAction()),
                            Map.of("metric", s.getMetric(), "action", a.getAction(), "outcome", "SKIPPED"));
                } else {
                    audit.engine("LIMIT_FREEZE_TRIGGER", "Application", s.getApplicationReference(),
                            "Froze %d/%d limit node(s) for %s (%s on %s) -> limit-service".formatted(
                                    frozen.affectedCount(), frozen.totalNodes(),
                                    s.getApplicationReference(), a.getAction(), s.getMetric()),
                            Map.of("metric", s.getMetric(), "reason", a.getReason(),
                                    "frozenCount", frozen.affectedCount(), "totalNodes", frozen.totalNodes()));
                }
            }
            default -> { }
        }
        audit.human(actor, "COVENANT_ACTION_APPROVED", "CovenantAction", String.valueOf(actionId),
                "Approved %s for %s".formatted(a.getAction(), s.getMetric()),
                Map.of("action", a.getAction(), "scheduleStatus", s.getStatus()));
        return actions.save(a);
    }

    @Transactional(readOnly = true)
    public List<CovenantAction> actionsFor(Long scheduleId) {
        return actions.findByScheduleIdOrderByRequestedAtDesc(scheduleId);
    }

    private CovenantSchedule get(Long id) {
        return schedules.findById(id).orElseThrow(() -> ApiException.notFound("No schedule: " + id));
    }

    private LocalDate addPeriod(LocalDate from, String freq) {
        return switch (freq == null ? "QUARTERLY" : freq.toUpperCase()) {
            case "MONTHLY" -> from.plus(1, ChronoUnit.MONTHS);
            case "HALF_YEARLY", "SEMI_ANNUAL" -> from.plus(6, ChronoUnit.MONTHS);
            case "ANNUAL" -> from.plus(1, ChronoUnit.YEARS);
            default -> from.plus(3, ChronoUnit.MONTHS);
        };
    }

    private boolean satisfies(double observed, String op, double threshold) {
        return switch (op) {
            case ">=" -> observed >= threshold;
            case ">" -> observed > threshold;
            case "<=" -> observed <= threshold;
            case "<" -> observed < threshold;
            case "==" -> Math.abs(observed - threshold) < 1e-9;
            default -> true;
        };
    }
}
