package com.helix.risk.client;

import com.helix.risk.service.MasterScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Configurable, parameter-routed scoring-approval policy engine. Reads the ordered,
 * first-match-wins {@code SCORING_APPROVAL_POLICY} master from config-service and resolves,
 * for a scored rating, whether approval is required and by which authority.
 *
 * <p>A rule matches when ALL of its present conditions hold. Condition keys (all optional):
 * {@code exposureGte}/{@code exposureLte}, {@code gradeIn}(list)/{@code gradeWorseThan}(ladder),
 * {@code scoreBandIn}, {@code overrideNotchesGte}, {@code overriddenEq}(bool), {@code segmentIn},
 * {@code jurisdictionIn}. The grade ladder is the shared {@link MasterScale} (best→worst).</p>
 *
 * <p><b>Caching &amp; outage behaviour</b> mirrors {@link com.helix.common.rbac.ActorDirectory} /
 * {@link com.helix.common.validate.ConfigValidator}: one snapshot fetch of the full
 * SCORING_APPROVAL_POLICY list per TTL window (lock-guarded), stale-served on refresh failure.
 * On a cold-start outage (no snapshot ever fetched) — or when no policy record exists — this
 * <b>fails open to the flat legacy behaviour</b>: approval required, authority {@code CREDIT_OFFICER}
 * (exactly the pre-existing mandatory CREDIT_OFFICER confirm gate). No advisory figure is ever moved
 * by this engine — it only routes an approval.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class ScoringApprovalPolicyClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringApprovalPolicyClient.class);

    /** The flat, behaviour-preserving fallback: the legacy mandatory CREDIT_OFFICER confirm gate. */
    public static final Resolution FLAT = new Resolution(true, "CREDIT_OFFICER", "flat-default");

    private final RestClient client;
    private final long ttlSeconds;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot;   // null until the first successful fetch

    public ScoringApprovalPolicyClient(@Value("${helix.config-service.base-url}") String baseUrl,
                                       @Value("${helix.scoring-approval.cache-ttl-seconds:5}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /** The scored rating's parameters the policy routes on. */
    public record Params(double exposure, String grade, String scoreBand, int overrideNotches,
                         boolean overridden, String segment, String jurisdiction) {
    }

    /** The routing outcome: whether approval is required, by which authority, and which rule matched. */
    public record Resolution(boolean requireApproval, String requiredAuthority, String ruleId) {
    }

    /**
     * Resolves the approval routing for a scored rating. First-match-wins over the ordered rules of
     * the most-specific policy record (jurisdiction match, else the default record). Fails open to
     * {@link #FLAT} when config-service has never been reachable or no policy record exists.
     */
    @SuppressWarnings("unchecked")
    public Resolution resolve(Params p) {
        List<Map<String, Object>> recs = records();
        if (recs == null || recs.isEmpty()) {
            return FLAT;   // cold-start outage or no policy seeded — legacy flat behaviour
        }
        Map<String, Object> chosen = choosePolicyRecord(recs, p.jurisdiction());
        if (chosen == null || !(chosen.get("payload") instanceof Map<?, ?> payload)
                || !(payload.get("rules") instanceof List<?> rules) || rules.isEmpty()) {
            return FLAT;
        }
        try {
            for (Object r : rules) {
                if (!(r instanceof Map<?, ?> rule)) continue;
                Object when = rule.get("when");
                Map<String, Object> conds = when instanceof Map<?, ?> w ? (Map<String, Object>) w : Map.of();
                if (matches(conds, p)) {
                    boolean require = !Boolean.FALSE.equals(rule.get("requireApproval"));
                    Object auth = rule.get("approverAuthority");
                    String authority = require ? (auth == null ? "CREDIT_OFFICER" : str(auth)) : null;
                    Object id = rule.get("id");
                    return new Resolution(require, authority, id == null ? "?" : str(id));
                }
            }
        } catch (Exception e) {
            log.warn("SCORING_APPROVAL_POLICY evaluation failed ({}); flat CREDIT_OFFICER fallback", e.getMessage());
            return FLAT;
        }
        return FLAT;   // no rule matched — legacy flat behaviour
    }

    // ---- rule matching -----------------------------------------------------------------------

    private boolean matches(Map<String, Object> when, Params p) {
        for (Map.Entry<String, Object> c : when.entrySet()) {
            if (!conditionHolds(c.getKey(), c.getValue(), p)) return false;
        }
        return true;   // all present conditions held (an empty {} always matches — the default rule)
    }

    private boolean conditionHolds(String key, Object val, Params p) {
        switch (key) {
            case "exposureGte": return p.exposure() >= num(val);
            case "exposureLte": return p.exposure() <= num(val);
            case "overrideNotchesGte": return Math.abs(p.overrideNotches()) >= num(val);
            case "overriddenEq": return p.overridden() == bool(val);
            case "gradeWorseThan": return gradeWorseThan(p.grade(), str(val));
            case "gradeIn": return inList(val, p.grade());
            case "scoreBandIn": return inList(val, p.scoreBand());
            case "segmentIn": return inList(val, p.segment());
            case "jurisdictionIn": return inList(val, p.jurisdiction());
            default:
                log.warn("Unknown SCORING_APPROVAL_POLICY condition '{}' — ignored (does not block match)", key);
                return true;   // unknown key never blocks the match (fail-open on config drift)
        }
    }

    /** True when {@code grade} is strictly worse (higher ladder index) than {@code threshold}. */
    private boolean gradeWorseThan(String grade, String threshold) {
        try {
            return MasterScale.index(grade) > MasterScale.index(threshold);
        } catch (RuntimeException e) {
            return false;   // unknown grade never spuriously escalates
        }
    }

    private boolean inList(Object val, String candidate) {
        if (candidate == null) return false;
        if (val instanceof List<?> list) {
            for (Object o : list) {
                if (candidate.equalsIgnoreCase(str(o))) return true;
            }
        }
        return false;
    }

    /** Prefer a record whose jurisdiction matches; else the jurisdiction-agnostic default; else the first. */
    private Map<String, Object> choosePolicyRecord(List<Map<String, Object>> recs, String jurisdiction) {
        Map<String, Object> fallback = null;
        for (Map<String, Object> rec : recs) {
            String jur = rec.get("jurisdiction") == null ? "" : String.valueOf(rec.get("jurisdiction")).trim();
            if (jurisdiction != null && jurisdiction.equals(jur)) return rec;   // most specific
            if (jur.isEmpty() && fallback == null) fallback = rec;              // default record
        }
        return fallback != null ? fallback : recs.get(0);
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o).trim()); } catch (RuntimeException e) { return Double.NaN; }
    }

    private static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ---- TTL-cached snapshot (ActorDirectory pattern) ----------------------------------------

    @SuppressWarnings("unchecked")
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
                List<Map<String, Object>> recs = client.get()
                        .uri("/api/masters/SCORING_APPROVAL_POLICY")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                snapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("SCORING_APPROVAL_POLICY refresh failed ({}); serving stale snapshot", e.getMessage());
                    return s.records;
                }
                log.warn("SCORING_APPROVAL_POLICY fetch failed and no snapshot cached ({}); flat fallback",
                        e.getMessage());
                return null;
            }
        }
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) {
    }
}
