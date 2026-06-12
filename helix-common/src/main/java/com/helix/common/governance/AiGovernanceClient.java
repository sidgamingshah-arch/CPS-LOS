package com.helix.common.governance;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
 *   <li>No record at all (config-service reachable, capability not governed yet):
 *       enabled — the seeded catalogue normally covers every capability.</li>
 * </ol>
 *
 * <p><b>Caching &amp; outage behaviour.</b> The full {@code AI_GOVERNANCE} list is
 * fetched once per TTL window (one HTTP call serves every capability/jurisdiction
 * lookup), guarded by a lock so concurrent expiry doesn't stampede config-service.
 * If a refresh fails the <em>last-known-good snapshot is served stale</em> — an
 * outage therefore never flips a disabled capability back to enabled. Only when no
 * snapshot has ever been fetched (config-service down since boot) does the
 * fallback apply, and it is per-capability: keys listed in
 * {@code helix.ai-governance.fail-closed-capabilities} resolve to <b>disabled</b>
 * (fail-closed — the extraction/overlay capabilities whose output sits next to
 * authoritative figures), everything else stays enabled (fail-open).</p>
 *
 * <p>Wire-in: each AI service that needs gating calls {@link #enforce} at the head
 * of the endpoint; a disabled capability raises a {@code 403} via
 * {@link ApiException#forbiddenAutonomy}. {@link #invalidate} drops the snapshot —
 * exposed per-service via {@code POST /api/governance/ai/cache/invalidate} so an
 * approved toggle can take effect immediately instead of waiting out the TTL.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class AiGovernanceClient {

    private static final Logger log = LoggerFactory.getLogger(AiGovernanceClient.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final Set<String> failClosed;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot;     // null until the first successful fetch

    public AiGovernanceClient(@Value("${helix.config-service.base-url}") String baseUrl,
                              @Value("${helix.ai-governance.cache-ttl-seconds:5}") long ttlSeconds,
                              @Value("${helix.ai-governance.fail-closed-capabilities:"
                                      + "doc-intel,collateral-intel,rag-overlay,macro-impact}")
                              String failClosedCsv) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
        this.failClosed = Arrays.stream(failClosedCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True iff the capability is enabled (for the given jurisdiction, or default). */
    public boolean isEnabled(AiCapability cap, String jurisdiction) {
        List<Map<String, Object>> recs = records();
        if (recs == null) {
            // Never fetched successfully — config-service down since boot.
            boolean closed = failClosed.contains(cap.key());
            log.warn("AI_GOVERNANCE unavailable and no cached snapshot — '{}' treated as {} ({})",
                    cap.key(), closed ? "DISABLED" : "enabled",
                    closed ? "fail-closed capability" : "fail-open");
            return !closed;
        }
        return resolveFrom(recs, cap.key(), jurisdiction);
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
        var out = new LinkedHashMap<String, Boolean>();
        for (AiCapability c : AiCapability.values()) {
            out.put(c.key(), isEnabled(c, jurisdiction));
        }
        return out;
    }

    /** Drop the snapshot so the next lookup re-fetches. Exposed per-service via the cache endpoint. */
    public void invalidate() {
        snapshot = null;
    }

    /**
     * The current AI_GOVERNANCE record list: fresh snapshot, else refresh, else the
     * stale snapshot (outage), else {@code null} (never fetched).
     */
    private List<Map<String, Object>> records() {
        Snapshot s = snapshot;
        if (s != null && s.until.isAfter(Instant.now())) {
            return s.records;
        }
        synchronized (refreshLock) {
            s = snapshot;                       // double-check under the lock
            if (s != null && s.until.isAfter(Instant.now())) {
                return s.records;
            }
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> recs = client.get()
                        .uri("/api/masters/AI_GOVERNANCE")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                snapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    // Serve stale rather than fail open/closed — a disabled capability
                    // stays disabled through a config-service outage.
                    log.warn("AI_GOVERNANCE refresh failed ({}); serving stale snapshot from {}",
                            e.getMessage(), s.until.minusSeconds(ttlSeconds));
                    return s.records;
                }
                log.warn("AI_GOVERNANCE fetch failed and no snapshot cached ({})", e.getMessage());
                return null;
            }
        }
    }

    private boolean resolveFrom(List<Map<String, Object>> recs, String key, String jurisdiction) {
        Boolean override = null;
        Boolean def = null;
        for (Map<String, Object> r : recs) {
            if (!key.equals(r.get("recordKey"))) continue;
            Object jur = r.get("jurisdiction");
            String jurStr = jur == null ? null : String.valueOf(jur);
            Boolean enabled = flag(r);
            if (jurisdiction != null && !jurisdiction.isBlank() && jurisdiction.equals(jurStr)) {
                override = enabled;
            } else if (jurStr == null || jurStr.isBlank()) {
                def = enabled;
            }
        }
        if (override != null) return override;
        if (def != null) return def;
        return true;    // not governed by any record — catalogue default is enabled
    }

    @SuppressWarnings("unchecked")
    private static Boolean flag(Map<String, Object> record) {
        Object payload = record.get("payload");
        if (!(payload instanceof Map<?, ?> p)) return true;
        Object enabled = ((Map<String, Object>) p).get("enabled");
        if (enabled instanceof Boolean b) return b;
        if (enabled instanceof String s) return Boolean.parseBoolean(s);
        return true;
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
