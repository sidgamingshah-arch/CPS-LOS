package com.helix.common.governance;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an {@link AiCapability}'s enabled flag from the {@code AI_GOVERNANCE}
 * master in config-service. A per-jurisdiction record overrides the default record
 * (key {@code <capability>}, jurisdiction null).
 *
 * <p><b>Resolution order</b> (first match wins):
 * <ol>
 *   <li>{@code AI_GOVERNANCE} record with {@code recordKey=<capability>.key()} and
 *       {@code jurisdiction=<jurisdiction>} (the override).</li>
 *   <li>{@code AI_GOVERNANCE} record with the same key and {@code jurisdiction=null}
 *       (the default).</li>
 *   <li>Conservative fallback: <b>enabled</b> — we never accidentally disable AI
 *       just because config-service is briefly unreachable.</li>
 * </ol>
 *
 * <p>Resolved values are cached in-process for {@value #TTL_SECONDS}s so we don't
 * hammer config-service on every request. Cache is keyed by (capability,
 * jurisdiction); the {@link #invalidate} hook lets the master admin endpoint clear
 * it after an approval.</p>
 *
 * <p>Wire-in: each AI service that needs gating calls {@link #enforce} at the head
 * of the endpoint; a disabled capability raises a {@code 403} via
 * {@link ApiException#forbiddenAutonomy}.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class AiGovernanceClient {

    private static final Logger log = LoggerFactory.getLogger(AiGovernanceClient.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public AiGovernanceClient(@Value("${helix.config-service.base-url}") String baseUrl,
                              @Value("${helix.ai-governance.cache-ttl-seconds:5}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /** True iff the capability is enabled (for the given jurisdiction, or default). */
    public boolean isEnabled(AiCapability cap, String jurisdiction) {
        String key = cacheKey(cap, jurisdiction);
        Cached c = cache.get(key);
        Instant now = Instant.now();
        if (c != null && c.until.isAfter(now)) {
            return c.enabled;
        }
        boolean enabled = resolve(cap, jurisdiction);
        cache.put(key, new Cached(enabled, now.plus(Duration.ofSeconds(ttlSeconds))));
        return enabled;
    }

    /**
     * Hard gate. Use at the head of any AI endpoint. {@code null} jurisdiction means
     * "I don't have a per-deal jurisdiction context" (admin / generic AI surfaces).
     */
    public void enforce(AiCapability cap, String jurisdiction) {
        if (!isEnabled(cap, jurisdiction)) {
            String suffix = jurisdiction == null || jurisdiction.isBlank() ? "" : " in " + jurisdiction;
            throw ApiException.forbiddenAutonomy(
                    "AI capability '" + cap.key() + "' is disabled" + suffix
                    + " (see /config/api/governance/ai)");
        }
    }

    /** Snapshot of every capability's resolved state for a given jurisdiction. */
    public Map<String, Boolean> resolvedMap(String jurisdiction) {
        var out = new java.util.LinkedHashMap<String, Boolean>();
        for (AiCapability c : AiCapability.values()) {
            out.put(c.key(), isEnabled(c, jurisdiction));
        }
        return out;
    }

    /** Drop the cache. Called by config-service hooks on master changes. */
    public void invalidate() {
        cache.clear();
    }

    private boolean resolve(AiCapability cap, String jurisdiction) {
        // 1. Try jurisdiction-specific override.
        if (jurisdiction != null && !jurisdiction.isBlank()) {
            Boolean v = readFlag(cap.key(), jurisdiction);
            if (v != null) return v;
        }
        // 2. Try the default record (jurisdiction=null on the master).
        Boolean def = readFlag(cap.key(), null);
        if (def != null) return def;
        // 3. Conservative fallback: enabled.
        return true;
    }

    @SuppressWarnings("unchecked")
    private Boolean readFlag(String key, String jurisdiction) {
        try {
            // The master API returns the active record (or 404 if absent).
            // We list and filter rather than relying on /{type}/{key} because that
            // returns the default record for a given key — we need to consider the
            // jurisdiction column too.
            List<Map<String, Object>> recs = client.get()
                    .uri(u -> u.path("/api/masters/AI_GOVERNANCE").build())
                    .retrieve()
                    .body(List.class);
            if (recs == null) return null;
            for (Map<String, Object> r : recs) {
                if (!key.equals(r.get("recordKey"))) continue;
                Object jur = r.get("jurisdiction");
                String jurStr = jur == null ? null : String.valueOf(jur);
                boolean matchesJurisdiction = jurisdiction == null
                        ? (jurStr == null || jurStr.isBlank())
                        : jurisdiction.equals(jurStr);
                if (!matchesJurisdiction) continue;
                Map<String, Object> payload = (Map<String, Object>) r.get("payload");
                if (payload == null) return true;
                Object enabled = payload.get("enabled");
                if (enabled instanceof Boolean b) return b;
                if (enabled instanceof String s) return Boolean.parseBoolean(s);
                return true;
            }
            return null;
        } catch (Exception e) {
            log.warn("AI_GOVERNANCE lookup failed for {}/{} ({}); treating as enabled (conservative)",
                    key, jurisdiction, e.getMessage());
            return null;
        }
    }

    private static String cacheKey(AiCapability cap, String jurisdiction) {
        return cap.key() + "@" + (jurisdiction == null ? "" : jurisdiction);
    }

    private record Cached(boolean enabled, Instant until) { }
}
