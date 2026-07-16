package com.helix.common.query;

import com.helix.common.audit.AuditService;
import com.helix.common.notify.NotificationService;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * The governed query / RFI collaboration lane, automatically present in every service that
 * includes helix-common. One {@link QueryThread} + an append-only {@link QueryMessage} log.
 *
 * <p><b>Internal</b> threads are in-app (surfaced through the addressee inbox). <b>External</b>
 * (customer / vendor) threads dispatch an RFI through the governed {@link NotificationService}
 * façade using the {@code RFI_REQUEST} EMAIL_TEMPLATE master — a missing master degrades to the
 * notification lane's raw fallback body (conservative fallback; never throws). Reminders and
 * schedule-later ride the existing notification machinery rather than a competing scheduler:
 * external reminders are configured on the RFI notification, and SCHEDULED threads are released
 * by {@link #dispatchDueScheduled()} which the platform notification sweep invokes.</p>
 *
 * <p><b>Governance</b>: every write stamps an audit event; only the raiser may resolve
 * (addressee self-resolve is a {@code forbiddenAutonomy} 403); resolution fans out to any
 * {@link QueryResolutionListener} bean in the owning service, in the same transaction,
 * best-effort.</p>
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);
    private static final String RFI_TEMPLATE = "RFI_REQUEST";
    private static final String SUBJECT = "QueryThread";

    private final QueryThreadRepository threads;
    private final QueryMessageRepository messages;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectProvider<QueryResolutionListener> listeners;

    public QueryService(QueryThreadRepository threads, QueryMessageRepository messages,
                        AuditService audit, NotificationService notifications,
                        ObjectProvider<QueryResolutionListener> listeners) {
        this.threads = threads;
        this.messages = messages;
        this.audit = audit;
        this.notifications = notifications;
        this.listeners = listeners;
    }

    /** Command to raise a thread; {@code scheduleInSeconds} wins over {@code scheduleAt} when both set. */
    public record Raise(String channel, String subjectType, String subjectRef, String topic,
                        String question, String addressee, String addresseeRole, Integer slaHours,
                        String scheduleAt, Long scheduleInSeconds, Integer reminderEveryHours,
                        Integer maxReminders, List<String> recipientRoles, String jurisdiction) {
    }

    /** Thread + its full ordered message log — the read model for a single thread. */
    public record View(QueryThread thread, List<QueryMessage> messages) {
    }

    @Transactional
    public View raise(Raise req, String actor) {
        QueryChannel channel = parseChannel(req.channel());
        QueryThread t = new QueryThread();
        t.setQueryRef(newRef());
        t.setChannel(channel);
        t.setSubjectType(req.subjectType());
        t.setSubjectRef(req.subjectRef());
        t.setTopic(req.topic());
        t.setQuestion(req.question());
        t.setRaisedBy(actor);
        t.setAddressee(req.addressee());
        t.setAddresseeRole(req.addresseeRole());
        t.setSlaHours(req.slaHours());
        t.setReminderEveryHours(req.reminderEveryHours());
        t.setMaxReminders(req.maxReminders());
        t.setRemindersSent(0);

        Instant now = Instant.now();
        Instant scheduleAt = resolveScheduleAt(req, now);
        boolean scheduled = scheduleAt != null && scheduleAt.isAfter(now);
        if (scheduled) {
            t.setScheduleAt(scheduleAt);
            t.setStatus(QueryStatus.SCHEDULED);
        } else {
            t.setStatus(QueryStatus.OPEN);
        }
        if (req.slaHours() != null && req.slaHours() > 0) {
            Instant from = scheduled ? scheduleAt : now;
            t.setDueAt(from.plusSeconds(req.slaHours() * 3600L));
        }
        QueryThread saved = threads.save(t);

        appendMessage(saved.getQueryRef(), actor, "HUMAN", req.question(), false);
        audit.human(actor, "QUERY_RAISED", SUBJECT, saved.getQueryRef(),
                "%s query %s raised on %s%s".formatted(channel, saved.getQueryRef(),
                        safe(req.subjectRef()), scheduled ? " (SCHEDULED)" : ""),
                Map.of("channel", channel.name(), "status", saved.getStatus().name(),
                        "subjectType", safe(req.subjectType()), "subjectRef", safe(req.subjectRef()),
                        "addressee", safe(req.addressee()), "addresseeRole", safe(req.addresseeRole()),
                        "topic", safe(req.topic()), "scheduled", scheduled));

        if (!scheduled && channel != QueryChannel.INTERNAL) {
            dispatchExternal(saved, actor, req.recipientRoles(), req.jurisdiction());
        }
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<QueryThread> list(String subjectRef, String addressee) {
        if (addressee != null && !addressee.isBlank()) return threads.findByAddresseeOrderByIdDesc(addressee);
        if (subjectRef != null && !subjectRef.isBlank()) return threads.findBySubjectRefOrderByIdDesc(subjectRef);
        return threads.findTop200ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public View get(String ref) {
        return view(require(ref));
    }

    /**
     * Deterministic query / RFI SLA rollup for THIS service's own {@code query_threads} table
     * (open / scheduled / responded / resolved / cancelled + overdue), broken down by channel.
     * A read-only report surface — no writes, no cross-service fan-out (each service exposes its
     * own auto-wired rollup). "Overdue" = a due date in the past on a still-outstanding thread
     * (anything not yet RESOLVED / CANCELLED).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> slaRollup() {
        Instant now = Instant.now();
        // Per-channel counters, slots: 0 total · 1 open · 2 scheduled · 3 responded · 4 resolved
        // · 5 cancelled · 6 overdue. Insertion order follows the enum for stable output.
        Map<QueryChannel, long[]> perChannel = new LinkedHashMap<>();
        for (QueryChannel c : QueryChannel.values()) perChannel.put(c, new long[7]);
        long[] all = new long[7];

        for (QueryThread t : threads.findAll()) {
            QueryStatus st = t.getStatus();
            long[] slot = perChannel.computeIfAbsent(t.getChannel(), k -> new long[7]);
            slot[0]++; all[0]++;
            int idx = switch (st) {
                case OPEN -> 1;
                case SCHEDULED -> 2;
                case RESPONDED -> 3;
                case RESOLVED -> 4;
                case CANCELLED -> 5;
            };
            slot[idx]++; all[idx]++;
            boolean outstanding = st != QueryStatus.RESOLVED && st != QueryStatus.CANCELLED;
            if (outstanding && t.getDueAt() != null && now.isAfter(t.getDueAt())) {
                slot[6]++; all[6]++;
            }
        }

        List<Map<String, Object>> byChannel = new ArrayList<>();
        for (Map.Entry<QueryChannel, long[]> e : perChannel.entrySet()) {
            long[] s = e.getValue();
            if (s[0] == 0) continue;
            byChannel.add(rollupRow(e.getKey().name(), s));
        }

        Map<String, Object> out = rollupRow(null, all);
        out.put("generatedAt", now);
        out.put("byChannel", byChannel);
        return out;
    }

    private static Map<String, Object> rollupRow(String channel, long[] s) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (channel != null) row.put("channel", channel);
        row.put("total", s[0]);
        row.put("open", s[1]);
        row.put("scheduled", s[2]);
        row.put("responded", s[3]);
        row.put("resolved", s[4]);
        row.put("cancelled", s[5]);
        row.put("overdue", s[6]);
        return row;
    }

    @Transactional
    public View reply(String ref, String body, String actor) {
        QueryThread t = require(ref);
        if (t.getStatus() == QueryStatus.RESOLVED || t.getStatus() == QueryStatus.CANCELLED) {
            throw ApiException.badRequest("Query " + ref + " is " + t.getStatus() + " — no further replies");
        }
        appendMessage(ref, actor, "HUMAN", body, false);
        t.setStatus(QueryStatus.RESPONDED);
        threads.save(t);
        audit.human(actor, "QUERY_REPLIED", SUBJECT, ref, "Reply posted on " + ref,
                Map.of("status", t.getStatus().name(), "author", actor));
        return view(t);
    }

    @Transactional
    public View resolve(String ref, String resolution, String actor) {
        QueryThread t = require(ref);
        if (!actor.equals(t.getRaisedBy())) {
            // SoD: an addressee (or anyone else) cannot self-resolve someone else's query.
            throw ApiException.forbiddenAutonomy("Only the raiser ('" + t.getRaisedBy()
                    + "') may resolve query " + ref + "; actor '" + actor + "' cannot self-resolve");
        }
        if (t.getStatus() == QueryStatus.CANCELLED) {
            throw ApiException.badRequest("Query " + ref + " is CANCELLED — cannot resolve");
        }
        t.setStatus(QueryStatus.RESOLVED);
        t.setResolvedBy(actor);
        t.setResolvedAt(Instant.now());
        t.setResolution(resolution);
        threads.save(t);
        appendMessage(ref, actor, "HUMAN", "RESOLVED: " + safe(resolution), false);
        audit.human(actor, "QUERY_RESOLVED", SUBJECT, ref, "Query " + ref + " resolved by " + actor,
                Map.of("resolvedBy", actor, "resolution", safe(resolution)));
        fireResolutionListeners(t);
        return view(t);
    }

    @Transactional
    public View cancel(String ref, String reason, String actor) {
        QueryThread t = require(ref);
        if (t.getStatus() == QueryStatus.RESOLVED) {
            throw ApiException.badRequest("Query " + ref + " is already RESOLVED — cannot cancel");
        }
        t.setStatus(QueryStatus.CANCELLED);
        threads.save(t);
        appendMessage(ref, actor, "HUMAN", "CANCELLED: " + safe(reason), false);
        audit.human(actor, "QUERY_CANCELLED", SUBJECT, ref, "Query " + ref + " cancelled by " + actor,
                Map.of("cancelledBy", actor, "reason", safe(reason)));
        return view(t);
    }

    /**
     * Simulated inbound callback from a customer portal / vendor reply (the external-lane
     * façade — no real transport). Appends an inbound message and flips the thread to RESPONDED.
     *
     * <p><b>Hardening.</b> This is the return path for external evidence (perfection / stock-audit
     * vendor reports), so it is deliberately constrained: it is only accepted on an
     * {@code EXTERNAL_CUSTOMER}/{@code EXTERNAL_VENDOR} thread that is still expecting a response
     * (non-terminal). An INTERNAL thread is a {@code 400} and a terminal thread is a {@code 409}.
     * The appended message is stamped author type {@code EXTERNAL} (never {@code HUMAN}), so a
     * forged/callback message is never recorded as a human action. A full per-thread inbound token
     * is a noted residual — this hardening removes the trivial forgery (any INTERNAL thread flipped
     * to RESPONDED with an arbitrary fake human author) without changing the legitimate flow.</p>
     */
    @Transactional
    public View externalResponse(String ref, String body, String from, String actor) {
        QueryThread t = require(ref);
        if (t.getChannel() == QueryChannel.INTERNAL) {
            throw ApiException.badRequest("external-response is only valid on an EXTERNAL_CUSTOMER/"
                    + "EXTERNAL_VENDOR thread; query " + ref + " is INTERNAL");
        }
        if (t.getStatus() == QueryStatus.RESOLVED || t.getStatus() == QueryStatus.CANCELLED) {
            throw ApiException.conflict("Query " + ref + " is " + t.getStatus()
                    + " — not expecting an inbound external response");
        }
        String author = (from != null && !from.isBlank()) ? from : "external";
        appendMessage(ref, author, "EXTERNAL", body, true);
        t.setStatus(QueryStatus.RESPONDED);
        threads.save(t);
        audit.human(actor, "QUERY_EXTERNAL_RESPONSE", SUBJECT, ref,
                "Inbound external response received on " + ref + " from " + author,
                Map.of("from", author, "channel", t.getChannel().name(), "status", t.getStatus().name()));
        return view(t);
    }

    /**
     * Platform-sweep hook (invoked from the shared notification sweep — <b>not</b> a competing
     * scheduler): releases SCHEDULED threads whose {@code scheduleAt} has passed to OPEN and,
     * for external lanes, dispatches the RFI now. Short transaction, idempotent (a released
     * thread leaves the SCHEDULED state so re-running is a no-op). Returns rows released.
     */
    @Transactional
    public int dispatchDueScheduled() {
        List<QueryThread> due = threads.findByStatusAndScheduleAtLessThanEqualOrderByIdAsc(
                QueryStatus.SCHEDULED, Instant.now());
        int released = 0;
        for (QueryThread t : due) {
            t.setStatus(QueryStatus.OPEN);
            threads.save(t);
            audit.engine("QUERY_DISPATCHED", SUBJECT, t.getQueryRef(),
                    "Scheduled %s query %s released to OPEN".formatted(t.getChannel(), t.getQueryRef()),
                    Map.of("channel", t.getChannel().name(), "scheduleAt", String.valueOf(t.getScheduleAt())));
            if (t.getChannel() != QueryChannel.INTERNAL) {
                dispatchExternal(t, "system", null, null);
            }
            released++;
        }
        return released;
    }

    // =============================================================== internals

    /** Render + enqueue the RFI through the notification façade; never throws (best-effort). */
    private void dispatchExternal(QueryThread t, String actor, List<String> roles, String jurisdiction) {
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("queryRef", t.getQueryRef());
            vars.put("channel", t.getChannel().name());
            vars.put("topic", safe(t.getTopic()));
            vars.put("question", safe(t.getQuestion()));
            vars.put("subjectType", safe(t.getSubjectType()));
            vars.put("subjectRef", safe(t.getSubjectRef()));
            vars.put("raisedBy", safe(t.getRaisedBy()));
            if (t.getAddressee() != null) vars.put("addressee", t.getAddressee());
            if (t.getDueAt() != null) vars.put("dueAt", t.getDueAt().toString());

            List<String> recipients = (roles != null && !roles.isEmpty())
                    ? roles
                    : (t.getAddresseeRole() != null ? List.of(t.getAddresseeRole()) : List.of());

            NotificationService.Enqueue cmd = new NotificationService.Enqueue(
                    RFI_TEMPLATE, RFI_TEMPLATE, SUBJECT, t.getQueryRef(),
                    "dispatch", jurisdiction, vars, recipients);
            // Reminders (if configured) ride the notification lane's own reminder machinery.
            notifications.enqueue(cmd, actor, null, t.getReminderEveryHours(), t.getMaxReminders());
        } catch (Exception e) {
            log.warn("RFI dispatch failed for {} ({})", t.getQueryRef(), e.getMessage());
        }
    }

    private void fireResolutionListeners(QueryThread t) {
        for (QueryResolutionListener l : listeners) {
            try {
                l.onResolved(t);
            } catch (Exception e) {
                log.warn("query resolution listener {} failed for {} ({})",
                        l.getClass().getSimpleName(), t.getQueryRef(), e.getMessage());
            }
        }
    }

    private QueryMessage appendMessage(String ref, String author, String authorType, String body,
                                       boolean inbound) {
        QueryMessage m = new QueryMessage();
        m.setQueryRef(ref);
        m.setAuthor(author == null ? "system" : author);
        m.setAuthorType(authorType);
        m.setBody(body);
        m.setInbound(inbound);
        return messages.save(m);
    }

    private View view(QueryThread t) {
        return new View(t, messages.findByQueryRefOrderByIdAsc(t.getQueryRef()));
    }

    private QueryThread require(String ref) {
        return threads.findByQueryRef(ref)
                .orElseThrow(() -> ApiException.notFound("Query " + ref + " not found"));
    }

    private static QueryChannel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw ApiException.badRequest("channel is required (INTERNAL | EXTERNAL_CUSTOMER | EXTERNAL_VENDOR)");
        }
        try {
            return QueryChannel.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown channel '" + channel
                    + "' (INTERNAL | EXTERNAL_CUSTOMER | EXTERNAL_VENDOR)");
        }
    }

    private static Instant resolveScheduleAt(Raise req, Instant now) {
        if (req.scheduleInSeconds() != null) return now.plusSeconds(req.scheduleInSeconds());
        if (req.scheduleAt() != null && !req.scheduleAt().isBlank()) {
            try {
                return Instant.parse(req.scheduleAt());
            } catch (Exception e) {
                throw ApiException.badRequest("scheduleAt must be an ISO-8601 instant: " + req.scheduleAt());
            }
        }
        return null;
    }

    private static String newRef() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("QRY-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
