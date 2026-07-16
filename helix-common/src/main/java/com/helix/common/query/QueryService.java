package com.helix.common.query;

import com.helix.common.audit.AuditService;
import com.helix.common.notify.NotificationService;
import com.helix.common.rbac.ActorDirectory;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Roles that grant unrestricted visibility over the query/RFI list (Fix 2). A supervisor /
     * admin / compliance / audit identity sees every thread on the service; everyone else is
     * scoped to threads they raised, are the named addressee of, or are directed at by role.
     */
    private static final Set<String> SUPERVISOR_ROLES = Set.of(
            "ADMIN", "SUPER", "SUPERVISOR", "CRO", "BOARD_COMMITTEE", "CREDIT_COMMITTEE",
            "CREDIT_HEAD", "RM_HEAD", "COLLECTIONS_HEAD", "COMPLIANCE", "AUDITOR");

    private final QueryThreadRepository threads;
    private final QueryMessageRepository messages;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectProvider<QueryResolutionListener> listeners;
    private final ObjectProvider<ActorDirectory> actorDirectory;

    public QueryService(QueryThreadRepository threads, QueryMessageRepository messages,
                        AuditService audit, NotificationService notifications,
                        ObjectProvider<QueryResolutionListener> listeners,
                        ObjectProvider<ActorDirectory> actorDirectory) {
        this.threads = threads;
        this.messages = messages;
        this.audit = audit;
        this.notifications = notifications;
        this.listeners = listeners;
        this.actorDirectory = actorDirectory;
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
            // dispatchExternal issues the one-time response token (stores its hash, embeds the
            // raw token in the RFI notification). Surface the raw token ONCE on the raise
            // response so the raiser / originating flow can build the tokenised callback.
            String rawToken = dispatchExternal(saved, actor, req.recipientRoles(), req.jurisdiction());
            saved.setResponseToken(rawToken);
            threads.save(saved);
        }
        return view(saved);
    }

    /**
     * Caller-scoped listing (Fix 2). The {@code actor} (verified X-Actor / token identity) sees
     * only threads they raised, are the named addressee of, or are directed at by a role they
     * hold — UNLESS they hold a supervisor/admin role, in which case the listing is unrestricted.
     * The optional {@code subjectRef} / {@code addressee} params are narrowing filters applied
     * WITHIN the caller's visible set, never a way to widen it.
     */
    @Transactional(readOnly = true)
    public List<QueryThread> list(String subjectRef, String addressee, String actor) {
        List<QueryThread> out = new ArrayList<>();
        for (QueryThread t : visibleTo(actor)) {
            if (addressee != null && !addressee.isBlank() && !addressee.equals(t.getAddressee())) continue;
            if (subjectRef != null && !subjectRef.isBlank() && !subjectRef.equals(t.getSubjectRef())) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * The caller-scoped visibility set. A supervisor/admin sees every thread; everyone else sees
     * only threads they raised, are the named addressee of, or are directed at by a role they
     * hold. Roles resolve via the optional {@link ActorDirectory} (config-service ACTOR_ROLE
     * master); when it is absent/unreachable the caller is treated as a non-supervisor with no
     * roles — never over-exposing, while their own raised/addressed threads stay visible.
     */
    private List<QueryThread> visibleTo(String actor) {
        Set<String> roles = rolesFor(actor);
        if (roles.stream().anyMatch(SUPERVISOR_ROLES::contains)) {
            return threads.findTop200ByOrderByIdDesc();
        }
        // Merge the three legs (raiser / named addressee / addressee-role); dedup by id, id-desc.
        TreeMap<Long, QueryThread> merged = new TreeMap<>(java.util.Comparator.reverseOrder());
        if (actor != null && !actor.isBlank()) {
            for (QueryThread t : threads.findByRaisedByOrderByIdDesc(actor)) merged.putIfAbsent(t.getId(), t);
            for (QueryThread t : threads.findByAddresseeOrderByIdDesc(actor)) merged.putIfAbsent(t.getId(), t);
        }
        if (!roles.isEmpty()) {
            for (QueryThread t : threads.findByAddresseeRoleInOrderByIdDesc(roles)) merged.putIfAbsent(t.getId(), t);
        }
        return new ArrayList<>(merged.values());
    }

    /** Resolve the actor's roles via the optional ActorDirectory; empty set on outage/absence. */
    private Set<String> rolesFor(String actor) {
        if (actor == null || actor.isBlank()) return Set.of();
        ActorDirectory dir = actorDirectory.getIfAvailable();
        if (dir == null) return Set.of();
        try {
            Set<String> roles = dir.rolesFor(actor);   // null = directory outage (fail-open) -> no roles
            return roles == null ? Set.of() : roles;
        } catch (Exception e) {
            // A fail-closed posture may throw on outage; for a read-only list we degrade to
            // "no roles" (caller still sees their own threads) rather than 403 the whole listing.
            log.warn("role resolution failed for '{}' while scoping queries ({})", actor, e.getMessage());
            return Set.of();
        }
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
     * forged/callback message is never recorded as a human action.</p>
     *
     * <p><b>Per-thread one-time token (Fix 1).</b> On top of the channel + non-terminal guards,
     * the caller must present the one-time {@code responseToken} issued for THIS thread at raise
     * time (embedded in the outbound RFI notification's tokenised callback link). A missing token
     * is a {@code 401}; a token that does not hash to the thread's stored hash — including a token
     * already spent — is a {@code 403}. On a successful response the hash is cleared, so the token
     * is single-use: a replay of the same token is rejected.</p>
     */
    @Transactional
    public View externalResponse(String ref, String body, String from, String token, String actor) {
        QueryThread t = require(ref);
        if (t.getChannel() == QueryChannel.INTERNAL) {
            throw ApiException.badRequest("external-response is only valid on an EXTERNAL_CUSTOMER/"
                    + "EXTERNAL_VENDOR thread; query " + ref + " is INTERNAL");
        }
        if (t.getStatus() == QueryStatus.RESOLVED || t.getStatus() == QueryStatus.CANCELLED) {
            throw ApiException.conflict("Query " + ref + " is " + t.getStatus()
                    + " — not expecting an inbound external response");
        }
        if (token == null || token.isBlank()) {
            throw ApiException.unauthorized("external-response to query " + ref
                    + " requires the one-time response token from the RFI callback link");
        }
        if (!tokenMatches(token, t.getResponseTokenHash())) {
            throw ApiException.forbiddenAutonomy("Invalid or already-used response token for query "
                    + ref + " — the callback link is single-use");
        }
        String author = (from != null && !from.isBlank()) ? from : "external";
        appendMessage(ref, author, "EXTERNAL", body, true);
        t.setStatus(QueryStatus.RESPONDED);
        t.setResponseTokenHash(null);   // single-use — invalidate so a replay of the token is rejected
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
                dispatchExternal(t, "system", null, null);   // issues the token hash on t
                threads.save(t);                             // persist the issued token hash
            }
            released++;
        }
        return released;
    }

    // =============================================================== internals

    /**
     * Issue the per-thread one-time response token, then render + enqueue the RFI through the
     * notification façade. The token's hash is stored on the thread (the raw token is embedded
     * in the notification as {@code responseToken} + a tokenised {@code callbackLink}, and
     * returned so the raiser can surface it once). Token issuance happens BEFORE the best-effort
     * enqueue so a notification-transport hiccup never leaves the thread without a usable token.
     * Never throws (best-effort dispatch). Returns the raw token.
     */
    private String dispatchExternal(QueryThread t, String actor, List<String> roles, String jurisdiction) {
        String rawToken = newResponseToken();
        t.setResponseTokenHash(hashToken(rawToken));
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
            // The tokenised callback the external party replies through — the RFI EMAIL_TEMPLATE
            // renders {{callbackLink}} / {{responseToken}}; a missing template still carries them
            // in the fallback body + persisted vars.
            vars.put("responseToken", rawToken);
            vars.put("callbackLink", "/api/queries/" + t.getQueryRef() + "/external-response?token=" + rawToken);

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
        return rawToken;
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

    /** A cryptographically-random, URL-safe one-time response token (256 bits of entropy). */
    private static String newResponseToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 (hex) of a raw token — only the hash is ever persisted on the thread. */
    private static String hashToken(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // never on a standard JRE
        }
    }

    /** Constant-time check that {@code provided} hashes to {@code storedHash} (null-safe -> false). */
    private static boolean tokenMatches(String provided, String storedHash) {
        if (provided == null || provided.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                hashToken(provided).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
