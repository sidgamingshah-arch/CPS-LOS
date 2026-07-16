package com.helix.common.notify;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The governed notification lane. {@link #enqueue} resolves an {@code EMAIL_TEMPLATE},
 * renders it deterministically from caller-supplied (already-authoritative) figures,
 * persists an idempotent outbox row, dispatches it through the pluggable
 * {@link NotificationTransport} (default {@link OutboxTransport}), and stamps a
 * {@code NOTIFICATION_ENQUEUED} SYSTEM audit event alongside. No AI, no figure mutation.
 *
 * <p>Idempotent per {@code (eventType, subjectRef, dedupeKey)} — re-firing a sweep returns
 * the existing row. A missing template degrades to a raw fallback body (never throws), and
 * a transport failure marks the row FAILED without propagating. Callers should still wrap
 * {@code enqueue} in a try/catch so a notification never fails the business operation.</p>
 *
 * <p><b>Schedule-later</b>: the {@code enqueue(cmd, actor, scheduleAt)} overload persists a
 * SCHEDULED row (no transport dispatch) when {@code scheduleAt} is in the future; the
 * {@link NotificationSweeper} (or {@code POST /api/notifications/sweep}) dispatches it once
 * due via {@link #dispatchDueScheduled()}. A null/past {@code scheduleAt} keeps the original
 * immediate-dispatch behaviour byte-identical.</p>
 *
 * <p><b>Auto-reminders</b>: when the NOTIFICATION_ROUTE master payload for the eventType
 * carries {@code {reminderEveryHours, maxReminders}} (or the caller passes them explicitly),
 * the row is marked reminder-eligible and {@link #sweepReminders()} re-enqueues reminder
 * notifications as NEW rows with the dedupeKey suffixed {@code #r<N>} — idempotent per
 * suffix, capped by {@code maxReminders}. Reminder rows never carry reminder config
 * themselves (no reminder-of-reminder chains).</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private final NotificationRepository repo;
    private final AuditService audit;
    private final NotificationTransport transport;
    private final ObjectProvider<NotificationTemplateResolver> resolver;

    public NotificationService(NotificationRepository repo, AuditService audit,
                               NotificationTransport transport,
                               ObjectProvider<NotificationTemplateResolver> resolver) {
        this.repo = repo;
        this.audit = audit;
        this.transport = transport;
        this.resolver = resolver;
    }

    /** A notification to enqueue; {@code recipientRoles}/{@code jurisdiction} may be null. */
    public record Enqueue(String eventType, String templateKey, String subjectType, String subjectRef,
                          String dedupeKey, String jurisdiction, Map<String, Object> vars,
                          List<String> recipientRoles) {
    }

    @Transactional
    public Notification enqueue(Enqueue cmd, String actor) {
        return enqueue(cmd, actor, null);
    }

    /**
     * Enqueue with optional deferred dispatch: a future {@code scheduleAt} persists the row
     * SCHEDULED (transport dispatch deferred to the sweep); null/past dispatches immediately
     * exactly as {@link #enqueue(Enqueue, String)} always has.
     */
    @Transactional
    public Notification enqueue(Enqueue cmd, String actor, Instant scheduleAt) {
        return enqueue(cmd, actor, scheduleAt, null, null);
    }

    /**
     * Full overload: explicit {@code reminderEveryHours}/{@code maxReminders} (both required
     * to take effect) override the NOTIFICATION_ROUTE master's reminder policy for this row.
     */
    @Transactional
    public Notification enqueue(Enqueue cmd, String actor, Instant scheduleAt,
                                Integer reminderEveryHours, Integer maxReminders) {
        return enqueueInternal(cmd, actor, scheduleAt, reminderEveryHours, maxReminders, true);
    }

    private Notification enqueueInternal(Enqueue cmd, String actor, Instant scheduleAt,
                                         Integer reminderEveryHours, Integer maxReminders,
                                         boolean applyRouteReminderPolicy) {
        String idem = cmd.eventType() + "|" + safe(cmd.subjectRef()) + "|" + safe(cmd.dedupeKey());
        Notification existing = repo.findByIdempotencyKey(idem).orElse(null);
        if (existing != null) return existing;   // idempotent — never duplicate

        Map<String, Object> vars = cmd.vars() == null ? Map.of() : cmd.vars();
        NotificationTemplateResolver r = resolver.getIfAvailable();
        String subject, body;
        boolean templateFound = false;
        if (r != null) {
            var tpl = r.template(cmd.templateKey(), cmd.jurisdiction());
            if (tpl.isPresent()) {
                subject = render(tpl.get().subject(), vars);
                body = render(tpl.get().body(), vars);
                templateFound = true;
            } else {
                subject = fallbackSubject(cmd);
                body = fallbackBody(cmd, vars);
            }
        } else {
            subject = fallbackSubject(cmd);
            body = fallbackBody(cmd, vars);
        }
        if (!templateFound) {
            safeAudit("NOTIFICATION_TEMPLATE_MISSING", cmd.subjectType(), cmd.subjectRef(),
                    "No EMAIL_TEMPLATE '" + cmd.templateKey() + "' — raw fallback body used",
                    Map.of("templateKey", String.valueOf(cmd.templateKey()), "eventType", cmd.eventType()));
        }

        List<String> roles = cmd.recipientRoles() != null && !cmd.recipientRoles().isEmpty()
                ? cmd.recipientRoles()
                : (r == null ? List.of() : r.recipientRoles(cmd.eventType(), cmd.jurisdiction()));

        Notification n = new Notification();
        n.setEventType(cmd.eventType());
        n.setSubjectType(cmd.subjectType());
        n.setSubjectRef(cmd.subjectRef());
        n.setDedupeKey(cmd.dedupeKey());
        n.setIdempotencyKey(idem);
        n.setTemplateKey(cmd.templateKey());
        n.setRecipientRoles(roles);
        n.setRenderedSubject(subject);
        n.setRenderedBody(body);
        n.setVars(new LinkedHashMap<>(vars));
        n.setTransport(transport.name());
        n.setStatus("PENDING");
        n.setCreatedBy(actor == null ? "system" : actor);
        applyReminderConfig(n, cmd, r, reminderEveryHours, maxReminders, applyRouteReminderPolicy);

        if (scheduleAt != null && scheduleAt.isAfter(Instant.now())) {
            n.setStatus("SCHEDULED");
            n.setScheduledFor(scheduleAt);
            Notification scheduled = repo.save(n);
            safeAudit("NOTIFICATION_SCHEDULED", cmd.subjectType(), cmd.subjectRef(),
                    "%s notification SCHEDULED for %s via %s%s".formatted(cmd.eventType(), scheduleAt,
                            scheduled.getTransport(), roles.isEmpty() ? "" : " to " + roles),
                    Map.of("eventType", cmd.eventType(), "templateKey", String.valueOf(cmd.templateKey()),
                            "status", scheduled.getStatus(), "scheduledFor", String.valueOf(scheduleAt),
                            "recipientRoles", roles, "notificationId", scheduled.getId()));
            return scheduled;
        }

        Notification saved = repo.save(n);
        dispatchAndRecord(saved);
        Notification done = repo.save(saved);

        safeAudit("NOTIFICATION_ENQUEUED", cmd.subjectType(), cmd.subjectRef(),
                "%s notification %s via %s%s".formatted(cmd.eventType(), done.getStatus(),
                        done.getTransport(), roles.isEmpty() ? "" : " to " + roles),
                Map.of("eventType", cmd.eventType(), "templateKey", String.valueOf(cmd.templateKey()),
                        "status", done.getStatus(), "transport", done.getTransport(),
                        "recipientRoles", roles, "notificationId", done.getId()));
        return done;
    }

    /** Runs the transport and records the outcome on the row. Never throws. */
    private void dispatchAndRecord(Notification saved) {
        try {
            NotificationTransport.Result res = transport.dispatch(saved);
            saved.setStatus(res.status());
            saved.setProviderRef(res.providerRef());
            saved.setFailureReason(res.failureReason());
            if ("SENT".equals(res.status())) saved.setSentAt(Instant.now());
        } catch (Exception e) {
            saved.setStatus("FAILED");
            saved.setFailureReason(e.getMessage());
            log.warn("notification transport failed for {} ({})", saved.getIdempotencyKey(), e.getMessage());
        }
    }

    /**
     * Marks the row reminder-eligible when the caller passed an explicit cadence+cap, or —
     * for non-reminder rows — when the NOTIFICATION_ROUTE payload carries
     * {@code {reminderEveryHours, maxReminders}}. Both values are required; never throws.
     */
    private void applyReminderConfig(Notification n, Enqueue cmd, NotificationTemplateResolver r,
                                     Integer reminderEveryHours, Integer maxReminders,
                                     boolean applyRouteReminderPolicy) {
        Integer every = reminderEveryHours;
        Integer max = maxReminders;
        if ((every == null || max == null) && applyRouteReminderPolicy && r != null) {
            try {
                var policy = r.reminderPolicy(cmd.eventType(), cmd.jurisdiction());
                if (policy.isPresent()) {
                    every = policy.get().reminderEveryHours();
                    max = policy.get().maxReminders();
                }
            } catch (Exception e) {
                log.warn("reminder policy lookup failed for {} ({})", cmd.eventType(), e.getMessage());
            }
        }
        if (every == null || max == null || every < 0 || max <= 0) return;
        n.setReminderEveryHours(every);
        n.setMaxReminders(max);
        n.setRemindersSent(0);
    }

    // =============================================================== sweep jobs

    /**
     * Sweep job (a): dispatch SCHEDULED rows whose {@code scheduledFor} has arrived through
     * the normal transport path, flipping them to the normal recorded status (SENT/FAILED).
     * Returns the number of rows dispatched. Short transaction; safe to re-run (a dispatched
     * row leaves the SCHEDULED state, so re-running is a no-op).
     */
    @Transactional
    public int dispatchDueScheduled() {
        List<Notification> due = repo.findByStatusAndScheduledForLessThanEqualOrderByIdAsc(
                "SCHEDULED", Instant.now());
        int dispatched = 0;
        for (Notification n : due) {
            dispatchAndRecord(n);
            Notification done = repo.save(n);
            safeAudit("NOTIFICATION_DISPATCHED", done.getSubjectType(), done.getSubjectRef(),
                    "Scheduled %s notification dispatched %s via %s".formatted(done.getEventType(),
                            done.getStatus(), done.getTransport()),
                    Map.of("eventType", done.getEventType(), "status", done.getStatus(),
                            "scheduledFor", String.valueOf(done.getScheduledFor()),
                            "notificationId", done.getId()));
            dispatched++;
        }
        return dispatched;
    }

    /**
     * Sweep job (b): for dispatched (SENT) rows explicitly marked reminder-eligible, enqueue
     * a NEW reminder notification with the dedupeKey suffixed {@code #r<N>} once the cadence
     * ({@code reminderEveryHours}; 0 = every sweep) has elapsed, capped by {@code maxReminders}.
     * Idempotent — the suffixed dedupeKey dedupes re-runs, and the parent's counters advance
     * only when a reminder is due. Reminder rows never carry reminder config themselves.
     * Returns the number of reminders created.
     */
    @Transactional
    public int sweepReminders() {
        Instant now = Instant.now();
        int created = 0;
        for (Notification parent : repo.findByStatusAndReminderEveryHoursIsNotNullOrderByIdAsc("SENT")) {
            Integer every = parent.getReminderEveryHours();
            Integer max = parent.getMaxReminders();
            if (every == null || max == null) continue;
            int sent = parent.getRemindersSent() == null ? 0 : parent.getRemindersSent();
            if (sent >= max) {                               // capped — retire from future scans
                parent.setReminderEveryHours(null);
                repo.save(parent);
                continue;
            }
            Instant baseline = parent.getLastReminderAt() != null ? parent.getLastReminderAt()
                    : (parent.getSentAt() != null ? parent.getSentAt() : parent.getCreatedAt());
            if (baseline == null || baseline.plusSeconds(every * 3600L).isAfter(now)) continue; // not due
            int seq = sent + 1;
            Map<String, Object> vars = new LinkedHashMap<>(
                    parent.getVars() == null ? Map.<String, Object>of() : parent.getVars());
            vars.put("reminderNumber", seq);
            vars.put("reminderOfNotificationId", parent.getId());
            // Jurisdiction is not persisted on the row; reminders render the default-jurisdiction template.
            Enqueue reminder = new Enqueue(parent.getEventType(), parent.getTemplateKey(),
                    parent.getSubjectType(), parent.getSubjectRef(),
                    safe(parent.getDedupeKey()) + "#r" + seq, null, vars, parent.getRecipientRoles());
            enqueueInternal(reminder, "system", null, null, null, false);
            parent.setRemindersSent(seq);
            parent.setLastReminderAt(now);
            if (seq >= max) parent.setReminderEveryHours(null);   // reached cap — retire from future scans
            repo.save(parent);
            created++;
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<Notification> list(String status, String eventType, String subjectRef) {
        if (status != null && !status.isBlank()) return repo.findByStatusOrderByIdDesc(status.toUpperCase());
        if (eventType != null && !eventType.isBlank()) return repo.findByEventTypeOrderByIdDesc(eventType);
        if (subjectRef != null && !subjectRef.isBlank()) {
            // subjectRef filter needs a type; fall back to scanning the recent window for the ref
            List<Notification> out = new ArrayList<>();
            for (Notification n : repo.findTop200ByOrderByIdDesc()) {
                if (subjectRef.equals(n.getSubjectRef())) out.add(n);
            }
            return out;
        }
        return repo.findTop200ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public Notification get(Long id) {
        return repo.findById(id).orElse(null);
    }

    // =============================================================== read-state (notification-center)

    /**
     * Count of unread notifications, optionally scoped to a {@code recipient} and/or {@code role}.
     * With no scope this is a fast {@code COUNT(*) WHERE read_at IS NULL}; with a scope the unread
     * rows are filtered in code because recipients/roles are persisted as JSON list columns.
     * Purely additive — enqueue/dispatch/reminder paths are untouched.
     */
    @Transactional(readOnly = true)
    public long unreadCount(String recipient, String role) {
        boolean noScope = (recipient == null || recipient.isBlank()) && (role == null || role.isBlank());
        if (noScope) return repo.countByReadAtIsNull();
        return repo.findByReadAtIsNullOrderByIdDesc().stream()
                .filter(n -> addressedTo(n, recipient, role))
                .count();
    }

    /**
     * Mark one notification read (human action, X-Actor). Idempotent — an already-read row keeps
     * its original {@code readAt}/{@code readBy}. Never deletes; the row stays in the outbox list.
     *
     * <p><b>Recipient ownership.</b> An enumerable id is not enough: to flip an unread row to read
     * the actor must be one of the row's {@code recipients} or hold one of its {@code recipientRoles}
     * (else {@code 403}). An already-read row is returned unchanged for any caller — there is no
     * read-state to flip, so idempotency is preserved without opening a forgery surface.</p>
     */
    @Transactional
    public Notification markRead(Long id, String actor) {
        Notification n = repo.findById(id).orElse(null);
        if (n == null) return null;
        if (n.getReadAt() != null) return n;            // idempotent — already read stays read (no flip to guard)
        String by = actor == null || actor.isBlank() ? "system" : actor;
        if (!addressedTo(n, by, by)) {
            throw ApiException.forbiddenAutonomy("Actor '" + by + "' is not a recipient of notification "
                    + id + " and cannot mark it read");
        }
        n.setReadAt(Instant.now());
        n.setReadBy(by);
        Notification saved = repo.save(n);
        safeHumanAudit(by, "NOTIFICATION_READ", saved.getSubjectType(), saved.getSubjectRef(),
                "%s notification marked read".formatted(saved.getEventType()),
                Map.of("eventType", String.valueOf(saved.getEventType()), "notificationId", saved.getId(),
                        "readBy", by));
        return saved;
    }

    /**
     * Mark every unread notification read that the {@code actor} is actually a recipient of (by id
     * or by one of its {@code recipientRoles}) — never someone else's inbox. An optional
     * {@code recipient} param can narrow further within the actor's own notifications. Returns how
     * many rows flipped. Idempotent — already-read rows are skipped, so re-running returns 0.
     */
    @Transactional
    public int markAllRead(String recipient, String actor) {
        String by = actor == null || actor.isBlank() ? "system" : actor;
        Instant now = Instant.now();
        boolean scoped = recipient != null && !recipient.isBlank();
        int count = 0;
        for (Notification n : repo.findByReadAtIsNullOrderByIdDesc()) {
            if (!addressedTo(n, by, by)) continue;                       // actor may only clear its own
            if (scoped && !addressedTo(n, recipient, recipient)) continue; // optional caller narrowing
            n.setReadAt(now);
            n.setReadBy(by);
            repo.save(n);
            count++;
        }
        if (count > 0) {
            safeHumanAudit(by, "NOTIFICATION_READ_ALL", "Notification", scoped ? recipient : "all",
                    "%d notification(s) marked read".formatted(count),
                    Map.of("count", count, "recipient", scoped ? recipient : ""));
        }
        return count;
    }

    /** True iff a row is addressed to {@code recipient} or {@code role} (either JSON list may carry it). */
    private static boolean addressedTo(Notification n, String recipient, String role) {
        if (listHas(n.getRecipients(), recipient) || listHas(n.getRecipientRoles(), recipient)) return true;
        return listHas(n.getRecipientRoles(), role) || listHas(n.getRecipients(), role);
    }

    private static boolean listHas(List<String> list, String value) {
        return value != null && !value.isBlank() && list != null && list.contains(value);
    }

    private String render(String template, Map<String, Object> vars) {
        if (template == null) return "";
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String fallbackSubject(Enqueue cmd) {
        return cmd.eventType().replace('_', ' ') + (cmd.subjectRef() == null ? "" : " — " + cmd.subjectRef());
    }

    private String fallbackBody(Enqueue cmd, Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder(cmd.eventType().replace('_', ' '));
        if (cmd.subjectRef() != null) sb.append(" for ").append(cmd.subjectRef());
        if (!vars.isEmpty()) {
            sb.append(" — ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Object> e : vars.entrySet()) parts.add(e.getKey() + "=" + e.getValue());
            sb.append(String.join(", ", parts));
        }
        return sb.toString();
    }

    private void safeAudit(String eventType, String subjectType, String subjectRef, String summary,
                           Map<String, Object> detail) {
        try {
            audit.engine(eventType, subjectType, subjectRef, summary, detail);
        } catch (Exception e) {
            log.warn("could not stamp {} audit ({})", eventType, e.getMessage());
        }
    }

    /** HUMAN-actor variant for the read-state gates (a named user clicked "read"). Never throws. */
    private void safeHumanAudit(String actor, String eventType, String subjectType, String subjectRef,
                                String summary, Map<String, Object> detail) {
        try {
            audit.human(actor, eventType, subjectType, subjectRef, summary, detail);
        } catch (Exception e) {
            log.warn("could not stamp {} audit ({})", eventType, e.getMessage());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
