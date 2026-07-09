package com.helix.common.notify;

import com.helix.common.audit.AuditService;
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
        Notification saved = repo.save(n);

        try {
            NotificationTransport.Result res = transport.dispatch(saved);
            saved.setStatus(res.status());
            saved.setProviderRef(res.providerRef());
            saved.setFailureReason(res.failureReason());
            if ("SENT".equals(res.status())) saved.setSentAt(Instant.now());
        } catch (Exception e) {
            saved.setStatus("FAILED");
            saved.setFailureReason(e.getMessage());
            log.warn("notification transport failed for {} ({})", idem, e.getMessage());
        }
        Notification done = repo.save(saved);

        safeAudit("NOTIFICATION_ENQUEUED", cmd.subjectType(), cmd.subjectRef(),
                "%s notification %s via %s%s".formatted(cmd.eventType(), done.getStatus(),
                        done.getTransport(), roles.isEmpty() ? "" : " to " + roles),
                Map.of("eventType", cmd.eventType(), "templateKey", String.valueOf(cmd.templateKey()),
                        "status", done.getStatus(), "transport", done.getTransport(),
                        "recipientRoles", roles, "notificationId", done.getId()));
        return done;
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
