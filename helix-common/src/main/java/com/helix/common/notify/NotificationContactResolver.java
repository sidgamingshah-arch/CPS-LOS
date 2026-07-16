package com.helix.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Resolves the physical delivery addresses (email / phone) for a notification's recipient
 * model — the {@code recipients} list (explicit actor names / addresses) and the
 * {@code recipientRoles} list (roles) persisted on the {@link Notification} row.
 *
 * <p>Two resolution sources, checked in order:</p>
 * <ol>
 *   <li><b>Direct</b> — a recipient string that already looks like an email ({@code contains "@"})
 *       or a phone (leading {@code +}/digits) is used verbatim. This path needs no config-service
 *       and is exposed as the static {@link #directEmails}/{@link #directPhones} for transports
 *       running without this bean.</li>
 *   <li><b>Contact master</b> — anything else (an actor name, or a role from {@code recipientRoles})
 *       is looked up in the {@code NOTIFICATION_CONTACT} master in config-service
 *       (recordKey = actor name OR role name, payload {@code {email, phone}}). TTL-cached and
 *       stale-served on outage, mirroring {@link NotificationTemplateResolver}; never throws.</li>
 * </ol>
 *
 * <p>Gated on {@code helix.config-service.base-url}: absent config-service ⇒ this bean is not
 * created and transports fall back to direct recognition only. A bank populates the
 * {@code NOTIFICATION_CONTACT} master to route role-addressed notifications to real inboxes.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class NotificationContactResolver {

    private static final Logger log = LoggerFactory.getLogger(NotificationContactResolver.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final Object lock = new Object();
    private volatile Snapshot contacts;

    public NotificationContactResolver(@Value("${helix.config-service.base-url}") String baseUrl,
                                       @Value("${helix.notify.cache-ttl-seconds:10}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /** Distinct email addresses for the recipients + roles (direct recognition then master lookup). */
    public List<String> emailsFor(List<String> recipients, List<String> roles) {
        return resolve(recipients, roles, "email", NotificationContactResolver::isEmail);
    }

    /** Distinct phone numbers for the recipients + roles (direct recognition then master lookup). */
    public List<String> phonesFor(List<String> recipients, List<String> roles) {
        return resolve(recipients, roles, "phone", NotificationContactResolver::isPhone);
    }

    private List<String> resolve(List<String> recipients, List<String> roles, String field,
                                 java.util.function.Predicate<String> direct) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (recipients != null) {
            for (String r : recipients) {
                if (r == null || r.isBlank()) continue;
                if (direct.test(r)) {
                    out.add(r.trim());
                } else {
                    String v = contactField(r, field);
                    if (v != null) out.add(v);
                }
            }
        }
        if (roles != null) {
            for (String role : roles) {
                if (role == null || role.isBlank()) continue;
                String v = contactField(role, field);
                if (v != null) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }

    // ---- static direct-recognition (used by transports when this bean is absent) --------------

    public static List<String> directEmails(List<String> recipients) {
        return directOnly(recipients, NotificationContactResolver::isEmail);
    }

    public static List<String> directPhones(List<String> recipients) {
        return directOnly(recipients, NotificationContactResolver::isPhone);
    }

    private static List<String> directOnly(List<String> recipients,
                                           java.util.function.Predicate<String> direct) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (recipients != null) {
            for (String r : recipients) {
                if (r != null && !r.isBlank() && direct.test(r)) out.add(r.trim());
            }
        }
        return new ArrayList<>(out);
    }

    public static boolean isEmail(String s) {
        return s != null && s.contains("@") && !s.isBlank();
    }

    public static boolean isPhone(String s) {
        return s != null && s.trim().matches("\\+?[0-9][0-9\\-\\s]{4,}");
    }

    // ---- NOTIFICATION_CONTACT master lookup ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private String contactField(String key, String field) {
        List<Map<String, Object>> recs = records();
        if (recs == null) return null;
        for (Map<String, Object> r : recs) {
            if (!key.equals(r.get("recordKey"))) continue;
            Object payload = r.get("payload");
            if (payload instanceof Map<?, ?> p) {
                Object v = ((Map<String, Object>) p).get(field);
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
            }
        }
        return null;
    }

    public void invalidate() {
        contacts = null;
    }

    private List<Map<String, Object>> records() {
        Snapshot s = contacts;
        if (s != null && s.until.isAfter(Instant.now())) return s.records;
        synchronized (lock) {
            s = contacts;
            if (s != null && s.until.isAfter(Instant.now())) return s.records;
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recs = client.get().uri("/api/masters/{t}", "NOTIFICATION_CONTACT")
                        .retrieve().body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                contacts = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("NOTIFICATION_CONTACT refresh failed ({}); serving stale", e.getMessage());
                    return s.records;
                }
                log.warn("NOTIFICATION_CONTACT fetch failed and no snapshot ({})", e.getMessage());
                return null;
            }
        }
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
