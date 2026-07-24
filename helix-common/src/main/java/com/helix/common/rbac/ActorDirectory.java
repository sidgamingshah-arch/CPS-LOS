package com.helix.common.rbac;

import com.helix.common.security.AuthContext;
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
        // Real-auth override (helix.security.mode=oidc|ldap): when the request carries a verified
        // credential, that identity's roles ARE the truth and win over the ACTOR_ROLE directory —
        // no config-service round-trip needed. Inert in the default 'none' profile (current()==null),
        // so the existing directory-resolution behaviour is preserved byte-identical.
        AuthContext.Identity authed = AuthContext.current();
        if (authed != null && authed.actor() != null && authed.actor().equals(actor)) {
            return new TreeSet<>(authed.roles());
        }
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
        Set<String> permitted = allowedRolesFor(action);
        boolean allowed = roles.stream().anyMatch(permitted::contains);
        if (!allowed) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' does not hold a role permitted to perform " + action.key()
                    + " (needs one of " + permitted + ", has " + roles
                    + ") — see the ACTOR_ROLE + ACTION_ROLE masters");
        }
    }

    /**
     * SOFT gate for broad first-line actions (e.g. origination) where the intent is "keep the
     * wrong line of defence OUT" rather than "allow only a whitelist". Denies ONLY when the
     * directory positively recognises the actor as holding roles that are ALL outside the action's
     * allowed set (e.g. a COMPLIANCE-only actor originating). An unroled / unknown actor (empty
     * roles) or a directory outage (null) is allowed — the gate narrows only for a role we
     * positively recognise, mirroring the frontend's default-permissive nav philosophy. Money
     * movement and named sign-offs must use the HARD {@link #require} instead.
     */
    public void requireRecognised(String actor, ProtectedAction action) {
        Set<String> roles = rolesFor(actor);
        if (roles == null || roles.isEmpty()) {
            // Outage (null) or unroled/unknown actor (empty) → permissive; only narrow for a
            // role the directory positively recognises. Name-equality SoD still applies elsewhere.
            return;
        }
        Set<String> permitted = allowedRolesFor(action);
        boolean allowed = roles.stream().anyMatch(permitted::contains);
        if (!allowed) {
            throw ApiException.forbiddenAutonomy(
                    "Actor '" + actor + "' holds only roles outside those permitted to " + action.key()
                    + " (needs one of " + permitted + ", has " + roles
                    + ") — origination is a first-line act; see the ACTOR_ROLE + ACTION_ROLE masters");
        }
    }

    // ---- action->role catalogue (admin-configurable ProtectedAction overrides) ---------------

    private volatile Snapshot actionRoleSnapshot;   // null until the first ACTION_ROLE fetch

    /**
     * Roles permitted to perform an action: the {@code ACTION_ROLE} master override (recordKey ==
     * {@link ProtectedAction#key()}, payload {@code roles:[..]}) when present and non-empty, else the
     * compile-time enum fallback ({@link ProtectedAction#allowedRoles()}). This makes "who may do
     * what" admin-editable as DATA (maker-checker) while the enum stays the safe default — behaviour
     * is byte-identical when the master mirrors the enum (as seeded) or is unavailable.
     */
    public Set<String> allowedRolesFor(ProtectedAction action) {
        List<Map<String, Object>> recs = actionRoleRecords();
        if (recs != null) {
            for (Map<String, Object> r : recs) {
                if (!action.key().equals(r.get("recordKey"))) continue;
                if (r.get("payload") instanceof Map<?, ?> p && p.get("roles") instanceof List<?> list
                        && !list.isEmpty()) {
                    Set<String> out = new TreeSet<>();
                    for (Object o : list) out.add(String.valueOf(o));
                    return out;
                }
            }
        }
        return action.allowedRoles();
    }

    /** Effective action → allowed-roles catalogue (master override where present, else enum), for the admin/governance view. */
    public Map<String, Object> catalogue() {
        List<Map<String, Object>> recs = actionRoleRecords();
        Map<String, Object> out = new LinkedHashMap<>();
        for (ProtectedAction a : ProtectedAction.values()) {
            boolean overridden = false;
            if (recs != null) {
                for (Map<String, Object> r : recs) {
                    if (a.key().equals(r.get("recordKey")) && r.get("payload") instanceof Map<?, ?> p
                            && p.get("roles") instanceof List<?> l && !l.isEmpty()) {
                        overridden = true;
                        break;
                    }
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", a.key());
            row.put("roles", new java.util.ArrayList<>(allowedRolesFor(a)));
            row.put("source", overridden ? "ACTION_ROLE_MASTER" : "ENUM_FALLBACK");
            out.put(a.name(), row);
        }
        return out;
    }

    private List<Map<String, Object>> actionRoleRecords() {
        Snapshot s = actionRoleSnapshot;
        if (s != null && s.until.isAfter(Instant.now())) {
            return s.records;
        }
        synchronized (refreshLock) {
            s = actionRoleSnapshot;
            if (s != null && s.until.isAfter(Instant.now())) {
                return s.records;
            }
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recs = client.get()
                        .uri("/api/masters/ACTION_ROLE")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                actionRoleSnapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("ACTION_ROLE refresh failed ({}); serving stale snapshot", e.getMessage());
                    return s.records;
                }
                log.warn("ACTION_ROLE fetch failed and no snapshot cached ({}) — using enum fallback", e.getMessage());
                return null;   // fall back to the compile-time enum
            }
        }
    }

    /** Drop both snapshots so the next lookup re-fetches. Exposed per-service via the cache endpoint. */
    public void invalidate() {
        snapshot = null;
        postureSnapshot = null;
        actionRoleSnapshot = null;
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
