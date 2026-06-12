package com.helix.common.rbac;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves an actor's roles from the {@code ACTOR_ROLE} master in config-service
 * (recordKey = actor name, payload {@code roles: [..]}), and enforces
 * {@link ProtectedAction} gates: 403 when the actor holds none of the action's
 * allowed roles. This layers ON TOP of name-equality SoD — the two checks answer
 * different questions ("may this person do this at all?" vs "are maker and
 * checker different people?") and both must pass.
 *
 * <p><b>Caching &amp; outage behaviour</b> mirrors
 * {@link com.helix.common.governance.AiGovernanceClient}: one snapshot fetch of
 * the full ACTOR_ROLE list per TTL window (lock-guarded), stale-served on refresh
 * failure. The cold-start fallback differs deliberately: with no snapshot ever
 * fetched we <b>fail open with a WARN</b> — blocking every human money movement
 * because config-service is restarting is the wrong failure mode, and the
 * name-equality SoD layer still applies. An actor that is simply absent from a
 * HEALTHY directory is denied — that is the point of a directory.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class ActorDirectory {

    private static final Logger log = LoggerFactory.getLogger(ActorDirectory.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot;     // null until the first successful fetch

    public ActorDirectory(@Value("${helix.config-service.base-url}") String baseUrl,
                          @Value("${helix.rbac.cache-ttl-seconds:5}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /** The actor's roles per the directory; empty set when unknown. Null when the directory is unavailable. */
    public Set<String> rolesFor(String actor) {
        List<Map<String, Object>> recs = records();
        if (recs == null) return null;
        Set<String> roles = new TreeSet<>();
        if (actor == null || actor.isBlank()) return roles;
        for (Map<String, Object> r : recs) {
            if (!actor.equals(r.get("recordKey"))) continue;
            Object payload = r.get("payload");
            if (payload instanceof Map<?, ?> p) {
                Object raw = p.get("roles");
                if (raw instanceof List<?> list) {
                    for (Object role : list) roles.add(String.valueOf(role));
                }
            }
        }
        return roles;
    }

    /**
     * Hard gate at the head of a role-protected transition. 403 when the actor
     * holds none of the action's allowed roles.
     */
    public void require(String actor, ProtectedAction action) {
        Set<String> roles = rolesFor(actor);
        if (roles == null) {
            // Cold-start outage — no directory ever fetched. Fail open, loudly:
            // humans must be able to work; name-equality SoD still applies.
            log.warn("ACTOR_ROLE directory unavailable and no snapshot cached — allowing '{}' for {} (fail-open)",
                    actor, action.key());
            return;
        }
        boolean allowed = roles.stream().anyMatch(action.allowedRoles()::contains);
        if (!allowed) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' does not hold a role permitted to perform " + action.key()
                    + " (needs one of " + action.allowedRoles() + ", has " + roles
                    + ") — see the ACTOR_ROLE master");
        }
    }

    /** Drop the snapshot so the next lookup re-fetches. Exposed per-service via the cache endpoint. */
    public void invalidate() {
        snapshot = null;
    }

    private List<Map<String, Object>> records() {
        Snapshot s = snapshot;
        if (s != null && s.until.isAfter(Instant.now())) {
            return s.records;
        }
        synchronized (refreshLock) {
            s = snapshot;
            if (s != null && s.until.isAfter(Instant.now())) {
                return s.records;
            }
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recs = client.get()
                        .uri("/api/masters/ACTOR_ROLE")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                snapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("ACTOR_ROLE refresh failed ({}); serving stale snapshot", e.getMessage());
                    return s.records;
                }
                log.warn("ACTOR_ROLE fetch failed and no snapshot cached ({})", e.getMessage());
                return null;
            }
        }
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
