package com.helix.common.fieldpolicy;

import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Config-driven dynamic screen behaviour. Field specs live in the {@code FIELD_POLICY}
 * master in config-service (recordKey = a form key, payload
 * {@code fields: [{field, label?, help?, hidden?, required?, visibleWhen?, requiredWhen?,
 * requiredSeverity?}]}) so a bank re-authors screen behaviour under maker-checker
 * instead of a code change. A spec can:
 * <ul>
 *   <li>override the field's {@code label} / {@code help} text;</li>
 *   <li>hide the field statically ({@code hidden:true}) or conditionally ({@code visibleWhen});</li>
 *   <li>make it required statically ({@code required:true}) or conditionally ({@code requiredWhen}),
 *       at ERROR (blocking) or WARN (advisory) {@code requiredSeverity}.</li>
 * </ul>
 *
 * <p>Condition ops (shared by {@code visibleWhen} / {@code requiredWhen}): {@code PRESENT}
 * (target field non-blank), {@code BLANK}, {@code EQ} (param {@code value}), {@code NE}
 * (param {@code value}), {@code IN} (param {@code values:[]}). {@code visibleWhen}/{@code requiredWhen}
 * absent ⇒ always visible / never conditionally-required.</p>
 *
 * <p><b>Caching &amp; outage behaviour</b> mirrors {@link com.helix.common.validate.ConfigValidator}:
 * one snapshot fetch of the full FIELD_POLICY list per TTL window (lock-guarded), stale-served
 * on refresh failure. On a cold-start outage (no snapshot ever fetched) enforcement is SKIPPED
 * with a WARN — business operations must never block on a config-service outage (fail-open).</p>
 *
 * <p><b>Server-side enforcement is authoritative.</b> The UI may hide/mark fields for convenience,
 * but {@link #enforce(String, Map)} is the gate the client cannot bypass.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class FieldPolicyService {

    private static final Logger log = LoggerFactory.getLogger(FieldPolicyService.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot;   // null until the first successful fetch

    public FieldPolicyService(@Value("${helix.config-service.base-url}") String baseUrl,
                              @Value("${helix.fieldpolicy.cache-ttl-seconds:5}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * The field specs for a form (each spec is the raw {@code {field, label?, help?, ...}} map).
     * Empty when the form has no policy or config-service has never been reachable (fail-open).
     */
    public List<Map<String, Object>> specs(String formKey) {
        List<Map<String, Object>> recs = records();
        if (recs == null) {
            log.warn("FIELD_POLICY unavailable and no snapshot cached — no specs for '{}' (fail-open)", formKey);
            return List.of();
        }
        for (Map<String, Object> rec : recs) {
            if (!formKey.equals(rec.get("recordKey"))) {
                continue;
            }
            if (rec.get("payload") instanceof Map<?, ?> payload
                    && payload.get("fields") instanceof List<?> fields) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object f : fields) {
                    if (f instanceof Map<?, ?> spec) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) spec;
                        out.add(m);
                    }
                }
                return out;
            }
        }
        return List.of();
    }

    /**
     * Enforces every conditional-required / static-required rule for the form's specs against the
     * supplied field values. For each field whose {@code requiredWhen} condition holds (or that is
     * statically {@code required:true}), a blank value produces a failure. ERROR failures aggregate
     * into a single {@link ApiException#badRequest} listing every failing field; WARN failures are
     * logged only. Fail-open: unknown formKey / no master / config outage ⇒ no-op.
     */
    public void enforce(String formKey, Map<String, Object> fields) {
        List<Map<String, Object>> specs = specs(formKey);
        if (specs.isEmpty()) {
            return;
        }
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            String field = str(spec.get("field"));
            if (field.isBlank()) {
                continue;
            }
            boolean required = Boolean.TRUE.equals(spec.get("required"));
            if (!required && spec.get("requiredWhen") instanceof Map<?, ?> cond) {
                required = holds(cond, fields);
            }
            if (!required) {
                continue;
            }
            Object raw = fields.get(field);
            boolean blank = raw == null || String.valueOf(raw).trim().isBlank();
            if (!blank) {
                continue;
            }
            String severity = spec.get("requiredSeverity") == null
                    ? "ERROR" : str(spec.get("requiredSeverity")).toUpperCase();
            String label = str(spec.get("label"));
            String message = (label.isBlank() ? field : label) + " is required";
            if ("WARN".equals(severity)) {
                log.warn("FIELD_POLICY[{}] advisory: {}", formKey, message);
            } else {
                errors.add(message);
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(
                    "Field policy [" + formKey + "]: " + String.join("; ", errors));
        }
    }

    /**
     * Evaluate a {@code visibleWhen} / {@code requiredWhen} condition against the current field
     * values. An unknown op is treated as not-holding (fail-open: never blocks).
     */
    private boolean holds(Map<?, ?> cond, Map<String, Object> fields) {
        String field = str(cond.get("field"));
        String op = str(cond.get("op")).toUpperCase();
        Object raw = fields.get(field);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        boolean present = !value.isBlank();
        return switch (op) {
            case "PRESENT" -> present;
            case "BLANK" -> !present;
            case "EQ" -> value.equals(str(cond.get("value")));
            case "NE" -> !value.equals(str(cond.get("value")));
            case "IN" -> cond.get("values") instanceof List<?> vals
                    && vals.stream().anyMatch(v -> value.equals(str(v)));
            default -> {
                log.warn("Unknown FIELD_POLICY condition op '{}' for field '{}' — treated as not-holding", op, field);
                yield false;
            }
        };
    }

    // ---- TTL-cached snapshot (ConfigValidator / ActorDirectory pattern) ------------------------

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
                        .uri("/api/masters/FIELD_POLICY")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                snapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("FIELD_POLICY refresh failed ({}); serving stale snapshot", e.getMessage());
                    return s.records;
                }
                log.warn("FIELD_POLICY fetch failed and no snapshot cached ({})", e.getMessage());
                return null;
            }
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
