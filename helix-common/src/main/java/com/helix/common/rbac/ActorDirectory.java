package com.helix.common.rbac;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final long postureTtlSeconds;
    private final boolean failClosedDefault;
    private final String serviceName;
    private final Object refreshLock = new Object();
    private final Object postureLock = new Object();
    private volatile Snapshot snapshot;         // null until the first successful ACTOR_ROLE fetch
    private volatile Snapshot postureSnapshot;  // null until the first successful GOVERNANCE_POSTURE fetch
    private volatile boolean simulateOutage;    // test-only: forces the real cold-start (records==null) branch

    public ActorDirectory(@Value("${helix.config-service.base-url}") String baseUrl,
                          @Value("${helix.rbac.cache-ttl-seconds:5}") long ttlSeconds,
                          @Value("${helix.rbac.posture-cache-ttl-seconds:5}") long postureTtlSeconds,
                          @Value("${helix.rbac.fail-closed-default:false}") boolean failClosedDefault,
                          @Value("${spring.application.name:unknown}") String serviceName) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
        this.postureTtlSeconds = postureTtlSeconds;
        this.failClosedDefault = failClosedDefault;
        this.serviceName = serviceName;
    }

    /**
     * The actor's roles per the directory; empty set when unknown. When the directory is
     * UNAVAILABLE the behaviour depends on the governance posture (G7): fail-open (default)
     * returns null so callers apply their conservative-but-permissive fallback; fail-closed
     * throws a 403 so the outage denies. A healthy directory is never affected either way.
     */
    public Set<String> rolesFor(String actor) {
        List<Map<String, Object>> recs = records();
        if (recs == null) {
            if (failClosed()) {
                throw ApiException.forbiddenPosture(actor);   // FAIL-CLOSED — deny on outage
            }
            return null;                                      // FAIL-OPEN — unchanged behaviour
        }
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

    /** Drop both snapshots so the next lookup re-fetches. Exposed per-service via the cache endpoint. */
    public void invalidate() {
        snapshot = null;
        postureSnapshot = null;
    }

    // ---- governance posture (G7) --------------------------------------------------------------

    /** True when the current posture is FAIL-CLOSED (deny on directory outage). Default false. */
    public boolean failClosed() {
        List<Map<String, Object>> recs = postureRecords();
        if (recs == null) return failClosedDefault;   // never fetched — cold-start property fallback
        for (Map<String, Object> r : recs) {
            if (!"rbac".equals(r.get("recordKey"))) continue;
            if (r.get("payload") instanceof Map<?, ?> p) {
                Object v = p.get("failClosed");
                if (v instanceof Boolean b) return b;
                if (v instanceof String s) return Boolean.parseBoolean(s);
            }
        }
        return failClosedDefault;
    }

    /** Examiner/health view of the effective posture on this service. */
    public Map<String, Object> postureStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", serviceName);
        out.put("failClosed", failClosed());
        out.put("source", postureSnapshot != null ? "MASTER" : "PROPERTY_FALLBACK");
        out.put("simulateOutage", simulateOutage);
        return out;
    }

    /** Test-only: forces the genuine cold-start outage branch without a real network failure. */
    public void setSimulateOutage(boolean on) {
        this.simulateOutage = on;
    }

    public boolean isSimulateOutage() {
        return simulateOutage;
    }

    private List<Map<String, Object>> postureRecords() {
        // NB: deliberately NOT gated by simulateOutage — an ACTOR_ROLE outage must not hide the
        // posture, or fail-closed could never be observed during the (simulated) directory outage.
        Snapshot s = postureSnapshot;
        if (s != null && s.until.isAfter(Instant.now())) {
            return s.records;
        }
        synchronized (postureLock) {
            s = postureSnapshot;
            if (s != null && s.until.isAfter(Instant.now())) {
                return s.records;
            }
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recs = client.get()
                        .uri("/api/masters/GOVERNANCE_POSTURE")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(postureTtlSeconds));
                postureSnapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("GOVERNANCE_POSTURE refresh failed ({}); serving stale posture", e.getMessage());
                    return s.records;
                }
                log.warn("GOVERNANCE_POSTURE fetch failed and no snapshot cached ({})", e.getMessage());
                return null;
            }
        }
    }

    private List<Map<String, Object>> records() {
        if (simulateOutage) return null;   // test-only cold-start simulation (real branch below)
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
