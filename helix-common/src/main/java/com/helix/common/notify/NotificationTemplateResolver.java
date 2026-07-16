package com.helix.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves an {@code EMAIL_TEMPLATE} (and optional {@code NOTIFICATION_ROUTE}) master from
 * config-service, mirroring {@link com.helix.common.governance.AiGovernanceClient}: a
 * TTL-cached full-list fetch per master type, served stale on outage, never throwing. A
 * most-specific {@code (recordKey, jurisdiction)} record wins over the default (null
 * jurisdiction). Absent config-service base-url ⇒ this bean is not created and the
 * {@link NotificationService} degrades to a raw fallback body.
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class NotificationTemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(NotificationTemplateResolver.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final Object lock = new Object();
    private volatile Snapshot templates;
    private volatile Snapshot routes;

    public NotificationTemplateResolver(@Value("${helix.config-service.base-url}") String baseUrl,
                                        @Value("${helix.notify.cache-ttl-seconds:10}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /** The resolved template {subject, body} for a key (+ optional jurisdiction), or empty. */
    public Optional<Template> template(String templateKey, String jurisdiction) {
        Map<String, Object> rec = pick(records("EMAIL_TEMPLATE", () -> templates, s -> templates = s),
                templateKey, jurisdiction);
        if (rec == null) return Optional.empty();
        Object payload = rec.get("payload");
        if (!(payload instanceof Map<?, ?> p)) return Optional.empty();
        Object subject = p.get("subject");
        Object body = p.get("body");
        return Optional.of(new Template(subject == null ? "" : String.valueOf(subject),
                body == null ? "" : String.valueOf(body)));
    }

    /** Recipient roles for an event from the NOTIFICATION_ROUTE master (recordKey = eventType), or empty. */
    @SuppressWarnings("unchecked")
    public List<String> recipientRoles(String eventType, String jurisdiction) {
        Map<String, Object> rec = pick(records("NOTIFICATION_ROUTE", () -> routes, s -> routes = s),
                eventType, jurisdiction);
        if (rec == null) return List.of();
        Object payload = rec.get("payload");
        if (!(payload instanceof Map<?, ?> p)) return List.of();
        Object roles = ((Map<String, Object>) p).get("roles");
        return roles instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    /**
     * Optional auto-reminder policy for an event from the NOTIFICATION_ROUTE master payload
     * ({@code {reminderEveryHours, maxReminders}}); empty when the route carries neither/partial
     * config or the value is malformed. Never throws.
     */
    public Optional<ReminderPolicy> reminderPolicy(String eventType, String jurisdiction) {
        Map<String, Object> rec = pick(records("NOTIFICATION_ROUTE", () -> routes, s -> routes = s),
                eventType, jurisdiction);
        if (rec == null) return Optional.empty();
        Object payload = rec.get("payload");
        if (!(payload instanceof Map<?, ?> p)) return Optional.empty();
        Integer every = asInt(p.get("reminderEveryHours"));
        Integer max = asInt(p.get("maxReminders"));
        if (every == null || max == null || every < 0 || max <= 0) return Optional.empty();
        return Optional.of(new ReminderPolicy(every, max));
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public void invalidate() {
        templates = null;
        routes = null;
    }

    private Map<String, Object> pick(List<Map<String, Object>> recs, String key, String jurisdiction) {
        if (recs == null) return null;
        Map<String, Object> override = null, def = null;
        for (Map<String, Object> r : recs) {
            if (!key.equals(r.get("recordKey"))) continue;
            Object jur = r.get("jurisdiction");
            String jurStr = jur == null ? null : String.valueOf(jur);
            if (jurisdiction != null && !jurisdiction.isBlank() && jurisdiction.equals(jurStr)) {
                override = r;
            } else if (jurStr == null || jurStr.isBlank()) {
                def = r;
            }
        }
        return override != null ? override : def;
    }

    private List<Map<String, Object>> records(String masterType, java.util.function.Supplier<Snapshot> get,
                                              java.util.function.Consumer<Snapshot> set) {
        Snapshot s = get.get();
        if (s != null && s.until.isAfter(Instant.now())) return s.records;
        synchronized (lock) {
            s = get.get();
            if (s != null && s.until.isAfter(Instant.now())) return s.records;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recs = client.get().uri("/api/masters/{t}", masterType)
                        .retrieve().body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                set.accept(fresh);
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("{} refresh failed ({}); serving stale", masterType, e.getMessage());
                    return s.records;
                }
                log.warn("{} fetch failed and no snapshot ({})", masterType, e.getMessage());
                return null;
            }
        }
    }

    public record Template(String subject, String body) { }

    /** Auto-reminder cadence/cap for an event ({@code reminderEveryHours} 0 = every sweep). */
    public record ReminderPolicy(int reminderEveryHours, int maxReminders) { }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
