package com.helix.decision.service;

import com.helix.common.audit.AuditService;
import com.helix.common.notify.NotificationService;
import com.helix.common.web.ApiException;
import com.helix.decision.client.UpstreamClient;
import com.helix.decision.client.UpstreamClient.CollateralViewDto;
import com.helix.decision.client.UpstreamClient.DealEnvelopeDto;
import com.helix.decision.dto.MerDtos.RaiseRequest;
import com.helix.decision.entity.CadCase;
import com.helix.decision.entity.ChecklistItem;
import com.helix.decision.entity.Deviation;
import com.helix.decision.entity.MerItem;
import com.helix.decision.repo.CadCaseRepository;
import com.helix.decision.repo.ChecklistItemRepository;
import com.helix.decision.repo.DeviationRepository;
import com.helix.decision.repo.MerItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitoring of Exceptions &amp; Renewals (MER) tracking workflow (PRD deferred-
 * documentation / conditions-subsequent / renewal monitoring). The register is built
 * from a completed CAD case (waived/deferred documents become follow-up obligations)
 * and from the deal's collateral (insurance, valuation, search reports and the annual
 * review become recurring renewals). Each item carries a due date, a reminder lead
 * time and an escalation window.
 *
 * <p>State machine: OPEN -&gt; SUBMITTED -&gt; CLEARED. The {@link #sweep} pushes past-due
 * items to OVERDUE, then to ESCALATED once the escalation window passes. Submitting
 * evidence emits a DMS feed event; clearance enforces segregation of duties
 * (verifier != submitter) and a recurring item rolls its due date forward for the
 * next cycle. A waiver requires an approver other than the owner.
 */
@Service
public class MerService {

    static final String OPEN = "OPEN";
    static final String SUBMITTED = "SUBMITTED";
    static final String CLEARED = "CLEARED";
    static final String OVERDUE = "OVERDUE";
    static final String ESCALATED = "ESCALATED";
    static final String WAIVED = "WAIVED";

    private static final List<String> ACTIVE = List.of(OPEN, SUBMITTED, OVERDUE, ESCALATED);
    private static final List<String> PENDING_DUE = List.of(OPEN, SUBMITTED);

    private final MerItemRepository items;
    private final CadCaseRepository cases;
    private final ChecklistItemRepository checklist;
    private final DeviationRepository deviations;
    private final UpstreamClient upstream;
    private final AuditService audit;
    private final NotificationService notifications;

    public MerService(MerItemRepository items, CadCaseRepository cases, ChecklistItemRepository checklist,
                      DeviationRepository deviations, UpstreamClient upstream, AuditService audit,
                      NotificationService notifications) {
        this.items = items;
        this.cases = cases;
        this.checklist = checklist;
        this.deviations = deviations;
        this.upstream = upstream;
        this.audit = audit;
        this.notifications = notifications;
    }

    private void safeNotify(NotificationService.Enqueue cmd, String actor) {
        try {
            notifications.enqueue(cmd, actor);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MerService.class)
                    .warn("notification enqueue failed for {} ({})", cmd.eventType(), e.getMessage());
        }
    }

    // --------------------------------------------------- build the register

    /**
     * Builds the monitoring register for a CAD case: every waived/deferred checklist
     * item becomes a deferred-document obligation, and the deal's collateral seeds the
     * recurring renewals (insurance, valuation, annual review). Idempotent per source.
     */
    @Transactional
    public List<MerItem> generateFromCad(Long caseId, String ownerOverride, String actor) {
        CadCase c = cases.findById(caseId).orElseThrow(() -> ApiException.notFound("No CAD case: " + caseId));
        String reference = c.getApplicationRef();
        String owner = ownerOverride != null && !ownerOverride.isBlank() ? ownerOverride
                : (c.getCreatedBy() == null ? "rm.user" : c.getCreatedBy());
        List<MerItem> created = new ArrayList<>();

        // 1. Deferred / waived documentation obligations from the CAD checklist.
        List<ChecklistItem> cli = checklist.findByCadCaseIdOrderByIdAsc(caseId);
        for (ChecklistItem item : cli) {
            if (!List.of("WAIVED", "DEVIATION").contains(item.getStatus())) {
                continue;
            }
            String src = "CAD:" + caseId + ":" + item.getCode();
            if (items.existsByApplicationReferenceAndSourceRef(reference, src)) continue;
            created.add(persist(reference, c.getCounterpartyName(), caseId, src, "DEFERRED_DOCUMENT", "DOCUMENT",
                    "Deferred document: " + item.getDescription(),
                    item.isMandatory() ? "HIGH" : "MEDIUM",
                    LocalDate.now().plusDays(90), false, null, owner, actor));
        }

        // 2. Recurring renewals from collateral (best-effort — origination may be offline).
        try {
            DealEnvelopeDto env = upstream.envelope(reference);
            if (env != null && env.collaterals() != null) {
                for (CollateralViewDto col : env.collaterals()) {
                    String tag = col.collateralType() == null ? "COLLATERAL" : col.collateralType();
                    addRenewal(reference, c.getCounterpartyName(), caseId,
                            "COL:" + col.id() + ":INSURANCE", "INSURANCE", "HIGH",
                            "Insurance renewal — " + tag + " (" + safe(col.description()) + ")",
                            LocalDate.now().plusYears(1), "ANNUAL", owner, actor, created);
                    LocalDate revalDue = parseOrNull(col.valuationDate());
                    addRenewal(reference, c.getCounterpartyName(), caseId,
                            "COL:" + col.id() + ":VALUATION", "VALUATION", "MEDIUM",
                            "Revaluation — " + tag + " (" + safe(col.description()) + ")",
                            revalDue == null ? LocalDate.now().plusYears(1) : revalDue.plusYears(1),
                            "ANNUAL", owner, actor, created);
                }
            }
        } catch (Exception ignore) {
            // collateral renewals are optional; the checklist-driven register stands on its own
        }

        // 3. The annual facility review — one per deal.
        addRenewal(reference, c.getCounterpartyName(), caseId, "REVIEW:ANNUAL", "RENEWAL_REVIEW", "HIGH",
                "Annual facility review", LocalDate.now().plusYears(1), "ANNUAL", owner, actor, created);

        audit.engine("MER_REGISTER_BUILT", "Application", reference,
                "Built %d MER item(s) from CAD case %d".formatted(created.size(), caseId),
                Map.of("created", created.size(), "cadCaseId", caseId));
        return created;
    }

    @Transactional
    public MerItem raise(RaiseRequest req, String actor) {
        LocalDate due;
        try {
            due = LocalDate.parse(req.dueDate());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("dueDate must be ISO yyyy-MM-dd");
        }
        boolean recurring = req.recurring();
        MerItem m = new MerItem();
        m.setApplicationReference(req.applicationRef());
        m.setCounterpartyName(req.counterpartyName());
        m.setSourceRef("MANUAL:" + Instant.now().toEpochMilli());
        m.setItemType(req.itemType() == null ? "CONDITION_SUBSEQUENT" : req.itemType().toUpperCase());
        m.setCategory(req.category() == null ? "CONDITION" : req.category().toUpperCase());
        m.setDescription(req.description());
        m.setCriticality(normaliseCriticality(req.criticality()));
        m.setDueDate(due);
        m.setRecurring(recurring);
        m.setRenewalFrequency(recurring ? defaultFreq(req.renewalFrequency()) : null);
        m.setReminderDaysBefore(req.reminderDaysBefore() == null ? 14 : Math.max(0, req.reminderDaysBefore()));
        m.setEscalationDaysAfter(req.escalationDaysAfter() == null ? 15 : Math.max(0, req.escalationDaysAfter()));
        m.setOwner(req.owner());
        m.setRaisedBy(actor);
        m.setStatus(OPEN);
        m.setCycleCount(0);
        MerItem saved = items.save(m);
        audit.human(actor, "MER_RAISED", "Application", req.applicationRef(),
                "%s due %s (owner %s)".formatted(m.getDescription(), due, m.getOwner()),
                Map.of("itemType", m.getItemType(), "criticality", m.getCriticality()));
        return saved;
    }

    // --------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<MerItem> list(String reference) {
        return items.findByApplicationReferenceOrderByDueDateAsc(reference);
    }

    @Transactional(readOnly = true)
    public List<MerItem> inbox(String owner, String status) {
        if (owner != null && status != null) {
            return items.findByOwnerAndStatusOrderByDueDateAsc(owner, status.toUpperCase());
        }
        if (owner != null) return items.findByOwnerOrderByDueDateAsc(owner);
        if (status != null) return items.findByStatusOrderByDueDateAsc(status.toUpperCase());
        return items.findByStatusInOrderByDueDateAsc(ACTIVE);
    }

    /** Counts by status (optionally scoped to a deal) for dashboards. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String reference) {
        List<MerItem> all = reference == null ? items.findAll()
                : items.findByApplicationReferenceOrderByDueDateAsc(reference);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of(OPEN, SUBMITTED, CLEARED, OVERDUE, ESCALATED, WAIVED)) {
            byStatus.put(s, all.stream().filter(m -> s.equals(m.getStatus())).count());
        }
        LocalDate today = LocalDate.now();
        long dueSoon = all.stream()
                .filter(m -> ACTIVE.contains(m.getStatus()))
                .filter(m -> !m.getDueDate().isBefore(today)
                        && !m.getDueDate().isAfter(today.plusDays(m.getReminderDaysBefore())))
                .count();
        return Map.of("total", all.size(), "byStatus", byStatus,
                "overdue", byStatus.get(OVERDUE) + byStatus.get(ESCALATED), "dueSoon", dueSoon);
    }

    // --------------------------------------------------- state transitions

    /** Owner submits the document/evidence — fed to the DMS, item moves to SUBMITTED. */
    @Transactional
    public MerItem submit(Long id, String docRef, String comment, String actor) {
        MerItem m = get(id);
        if (!ACTIVE.contains(m.getStatus())) {
            throw ApiException.conflict("Item is " + m.getStatus() + " — nothing to submit");
        }
        m.setStatus(SUBMITTED);
        m.setDocRef(docRef);
        m.setSubmittedBy(actor);
        m.setSubmittedAt(Instant.now());
        if (comment != null) m.setDecisionComment(comment);
        MerItem saved = items.save(m);
        audit.human(actor, "MER_SUBMITTED", "MerItem", String.valueOf(id),
                "Submitted '%s' (doc %s)".formatted(m.getDescription(), docRef), Map.of("docRef", docRef));
        // DMS feed — the document is pushed to the Document Management System.
        audit.engine("DMS_FEED", "Application", m.getApplicationReference(),
                "Document fed to DMS for '%s' (ref %s)".formatted(m.getDescription(), docRef),
                Map.of("docRef", docRef, "itemType", m.getItemType()));
        return saved;
    }

    /** Verifier clears (approve) or returns (reject) a submitted item. SoD enforced. */
    @Transactional
    public MerItem verify(Long id, boolean approve, String comment, String actor) {
        MerItem m = get(id);
        if (!SUBMITTED.equals(m.getStatus())) {
            throw ApiException.conflict("Only SUBMITTED items can be verified (is " + m.getStatus() + ")");
        }
        if (actor.equalsIgnoreCase(m.getSubmittedBy())) {
            throw ApiException.forbiddenAutonomy("Verifier cannot be the submitter (segregation of duties)");
        }
        m.setDecisionComment(comment);
        if (!approve) {
            m.setStatus(OPEN);
            m.setSubmittedBy(null);
            m.setSubmittedAt(null);
            m.setDocRef(null);
            audit.human(actor, "MER_RETURNED", "MerItem", String.valueOf(id),
                    "Returned '%s' for re-submission".formatted(m.getDescription()), Map.of());
            return items.save(m);
        }
        m.setClearedBy(actor);
        m.setClearedAt(Instant.now());
        if (m.isRecurring()) {
            int cycle = m.getCycleCount() + 1;
            rollForward(m);
            m.setCycleCount(cycle);
            audit.human(actor, "MER_RENEWED", "MerItem", String.valueOf(id),
                    "Cleared & rolled '%s' to %s (cycle %d)".formatted(m.getDescription(), m.getDueDate(), cycle),
                    Map.of("nextDue", m.getDueDate().toString(), "cycle", cycle));
        } else {
            m.setStatus(CLEARED);
            audit.human(actor, "MER_CLEARED", "MerItem", String.valueOf(id),
                    "Cleared '%s'".formatted(m.getDescription()), Map.of());
        }
        return items.save(m);
    }

    /** Documented waiver — closes the exception without a document. Verifier != owner. */
    @Transactional
    public MerItem waive(Long id, String reason, String actor) {
        MerItem m = get(id);
        if (!ACTIVE.contains(m.getStatus())) {
            throw ApiException.conflict("Item is " + m.getStatus() + " — cannot waive");
        }
        if (actor.equalsIgnoreCase(m.getOwner())) {
            throw ApiException.forbiddenAutonomy("Waiver approver cannot be the owner (segregation of duties)");
        }
        m.setStatus(WAIVED);
        m.setClearedBy(actor);
        m.setClearedAt(Instant.now());
        m.setDecisionComment(reason);
        MerItem saved = items.save(m);
        audit.human(actor, "MER_WAIVED", "MerItem", String.valueOf(id),
                "Waived '%s': %s".formatted(m.getDescription(), reason), Map.of("reason", reason));
        return saved;
    }

    /** Ages the register: past-due active items -> OVERDUE, then -> ESCALATED. */
    @Transactional
    public Map<String, Object> sweep(String actor) {
        LocalDate today = LocalDate.now();
        int overdue = 0;
        int escalated = 0;
        for (MerItem m : items.findByStatusInOrderByDueDateAsc(PENDING_DUE)) {
            if (today.isAfter(m.getDueDate())) {
                m.setStatus(OVERDUE);
                items.save(m);
                overdue++;
                audit.engine("MER_OVERDUE", "Application", m.getApplicationReference(),
                        "'%s' overdue since %s (owner %s)".formatted(m.getDescription(), m.getDueDate(), m.getOwner()),
                        Map.of("itemType", m.getItemType(), "criticality", m.getCriticality()));
                safeNotify(new NotificationService.Enqueue("MER_OVERDUE", "MER_OVERDUE",
                        "Application", m.getApplicationReference(),
                        "mer:" + m.getId() + ":overdue:" + m.getDueDate(), null,
                        Map.of("borrower", m.getApplicationReference(), "description", m.getDescription(),
                                "dueDate", m.getDueDate().toString(), "owner", m.getOwner()), null), actor);
            }
        }
        for (MerItem m : items.findByStatusOrderByDueDateAsc(OVERDUE)) {
            if (today.isAfter(m.getDueDate().plusDays(m.getEscalationDaysAfter()))) {
                m.setStatus(ESCALATED);
                items.save(m);
                escalated++;
                audit.engine("MER_ESCALATED", "Application", m.getApplicationReference(),
                        "'%s' escalated — overdue %d+ days (owner %s)".formatted(
                                m.getDescription(), m.getEscalationDaysAfter(), m.getOwner()),
                        Map.of("itemType", m.getItemType(), "criticality", m.getCriticality()));
                safeNotify(new NotificationService.Enqueue("MER_ESCALATED", "MER_ESCALATED",
                        "Application", m.getApplicationReference(),
                        "mer:" + m.getId() + ":escalated:" + m.getDueDate(), null,
                        Map.of("borrower", m.getApplicationReference(), "description", m.getDescription(),
                                "escalatedTo", m.getOwner(), "days", m.getEscalationDaysAfter()), null), actor);
            }
        }
        return Map.of("markedOverdue", overdue, "markedEscalated", escalated);
    }

    /**
     * SRM renewal hook (additive; invoked only by {@code SrmService} once it observes an
     * AUTHORIZED {@code SRM_RENEWAL} noting). Advances the next review / renewal due date
     * on the subject's register by one cycle for every <b>active {@code RENEWAL_REVIEW}</b>
     * item of the given reference, re-opening it for the fresh cycle. It touches nothing
     * else — no other item type, no other subject, no other transition — so every existing
     * MER flow is byte-identical for non-SRM callers. Returns the items advanced.
     */
    @Transactional
    public List<MerItem> advanceReviewForSrm(String reference, String triggerRef, String actor) {
        List<MerItem> advanced = new ArrayList<>();
        if (reference == null || reference.isBlank()) {
            return advanced;
        }
        for (MerItem m : items.findByApplicationReferenceOrderByDueDateAsc(reference)) {
            if (!"RENEWAL_REVIEW".equals(m.getItemType()) || !ACTIVE.contains(m.getStatus())) {
                continue;
            }
            LocalDate before = m.getDueDate();
            LocalDate after = addPeriod(before, m.getRenewalFrequency());
            m.setDueDate(after);
            m.setStatus(OPEN);
            m.setDocRef(null);
            m.setSubmittedBy(null);
            m.setSubmittedAt(null);
            m.setLastReminderAt(null);
            m.setCycleCount(m.getCycleCount() + 1);
            items.save(m);
            advanced.add(m);
            audit.human(actor, "MER_RENEWAL_ADVANCED", "MerItem", String.valueOf(m.getId()),
                    "SRM renewal %s advanced next review %s -> %s".formatted(triggerRef, before, after),
                    Map.of("trigger", triggerRef == null ? "" : triggerRef,
                            "before", before.toString(), "after", after.toString()));
        }
        return advanced;
    }

    /** Items whose due date falls within {@code days} from today and still active. */
    @Transactional(readOnly = true)
    public List<MerItem> upcoming(int days) {
        LocalDate today = LocalDate.now();
        return items.findByDueDateBetweenAndStatusInOrderByDueDateAsc(
                today, today.plusDays(Math.max(1, days)), ACTIVE);
    }

    /** Emits a near-due reminder per item (template-driven, audited). */
    @Transactional
    public int sendReminders(int days, String actor) {
        List<MerItem> due = upcoming(days);
        LocalDate today = LocalDate.now();
        for (MerItem m : due) {
            audit.engine("NOTIFICATION_SENT", "Application", m.getApplicationReference(),
                    "EMAIL_TEMPLATE MER_DUE: '%s' due %s -> %s".formatted(
                            m.getDescription(), m.getDueDate(), m.getOwner()),
                    Map.of("template", "MER_DUE", "itemType", m.getItemType(),
                            "dueDate", m.getDueDate().toString(), "owner", m.getOwner()));
            safeNotify(new NotificationService.Enqueue("MER_DUE", "MER_DUE",
                    "Application", m.getApplicationReference(),
                    "mer:" + m.getId() + ":due:" + m.getDueDate(), null,
                    Map.of("borrower", m.getApplicationReference(), "description", m.getDescription(),
                            "dueDate", m.getDueDate().toString(), "owner", m.getOwner()), null), actor);
            m.setLastReminderAt(today);
            items.save(m);
        }
        return due.size();
    }

    // --------------------------------------------------- helpers

    private void addRenewal(String reference, String cpName, Long caseId, String src, String type,
                            String criticality, String desc, LocalDate due, String freq, String owner,
                            String actor, List<MerItem> sink) {
        if (items.existsByApplicationReferenceAndSourceRef(reference, src)) return;
        sink.add(persist(reference, cpName, caseId, src, type, "RENEWAL", desc, criticality, due, true, freq, owner, actor));
    }

    private MerItem persist(String reference, String cpName, Long caseId, String src, String type, String category,
                            String desc, String criticality, LocalDate due, boolean recurring, String freq,
                            String owner, String actor) {
        MerItem m = new MerItem();
        m.setApplicationReference(reference);
        m.setCounterpartyName(cpName);
        m.setCadCaseId(caseId);
        m.setSourceRef(src);
        m.setItemType(type);
        m.setCategory(category);
        m.setDescription(desc);
        m.setCriticality(criticality);
        m.setDueDate(due);
        m.setRecurring(recurring);
        m.setRenewalFrequency(recurring ? defaultFreq(freq) : null);
        m.setReminderDaysBefore("RENEWAL".equals(category) ? 30 : 14);
        m.setEscalationDaysAfter("HIGH".equals(criticality) ? 7 : 15);
        m.setOwner(owner);
        m.setRaisedBy(actor);
        m.setStatus(OPEN);
        m.setCycleCount(0);
        return items.save(m);
    }

    private void rollForward(MerItem m) {
        m.setDueDate(addPeriod(m.getDueDate(), m.getRenewalFrequency()));
        m.setStatus(OPEN);
        m.setDocRef(null);
        m.setSubmittedBy(null);
        m.setSubmittedAt(null);
        m.setClearedBy(null);
        m.setClearedAt(null);
        m.setLastReminderAt(null);
    }

    private MerItem get(Long id) {
        return items.findById(id).orElseThrow(() -> ApiException.notFound("No MER item: " + id));
    }

    private LocalDate addPeriod(LocalDate from, String freq) {
        return switch (freq == null ? "ANNUAL" : freq.toUpperCase()) {
            case "MONTHLY" -> from.plus(1, ChronoUnit.MONTHS);
            case "QUARTERLY" -> from.plus(3, ChronoUnit.MONTHS);
            case "HALF_YEARLY", "SEMI_ANNUAL" -> from.plus(6, ChronoUnit.MONTHS);
            default -> from.plus(1, ChronoUnit.YEARS);
        };
    }

    private String defaultFreq(String freq) {
        return freq == null || freq.isBlank() ? "ANNUAL" : freq.toUpperCase();
    }

    private LocalDate parseOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String normaliseCriticality(String c) {
        if (c == null) return "MEDIUM";
        String u = c.toUpperCase();
        return List.of("HIGH", "MEDIUM", "LOW").contains(u) ? u : "MEDIUM";
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}
