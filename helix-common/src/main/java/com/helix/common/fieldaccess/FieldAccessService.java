package com.helix.common.fieldaccess;

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

/**
 * Field-level access control (U9). Access specs live in the {@code FIELD_ACCESS} master in
 * config-service (recordKey = a business-form key, payload
 * {@code roles: {<role>: {<field>: READ|WRITE|HIDDEN}}}) so a bank re-authors who may see /
 * edit which field under maker-checker instead of a code change.
 *
 * <p><b>DEFAULT-PERMISSIVE is the contract.</b> An unmapped form, an unmapped role, an
 * unmapped field, or a config-service outage all resolve to <b>full access</b> (every field
 * WRITE). This mirror keeps every existing flow byte-identical — the gate only ever
 * <i>narrows</i> access for a form+role that has been explicitly authored.</p>
 *
 * <p><b>Caching &amp; outage behaviour</b> mirrors {@link com.helix.common.fieldpolicy.FieldPolicyService}
 * and {@link com.helix.common.validate.ConfigValidator}: one snapshot fetch of the full
 * FIELD_ACCESS list per TTL window (lock-guarded), stale-served on refresh failure. On a
 * cold-start outage (no snapshot ever fetched) access resolves to full (fail-open) with a WARN —
 * business operations must never block on a config-service outage.</p>
 *
 * <p><b>Server-side enforcement is authoritative.</b> The UI may hide/grey fields for
 * convenience, but {@link #enforce(String, String, Map)} is the gate the client cannot bypass.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class FieldAccessService {

    private static final Logger log = LoggerFactory.getLogger(FieldAccessService.class);

    private final RestClient client;
    private final long ttlSeconds;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot;   // null until the first successful fetch

    public FieldAccessService(@Value("${helix.config-service.base-url}") String baseUrl,
                              @Value("${helix.fieldaccess.cache-ttl-seconds:5}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * The {@code field -> access} map for a form + role, access ∈ {@code READ|WRITE|HIDDEN}.
     * DEFAULT-PERMISSIVE: an unmapped form/role (or a config outage) yields an EMPTY map, which
     * the caller reads as full access. A field absent from a present map is likewise WRITE.
     */
    public Map<String, String> accessFor(String formKey, String role) {
        Map<String, String> out = new LinkedHashMap<>();
        if (formKey == null || role == null || formKey.isBlank() || role.isBlank()) {
            return out;
        }
        List<Map<String, Object>> recs = records();
        if (recs == null) {
            log.warn("FIELD_ACCESS unavailable and no snapshot cached — full access for form '{}' role '{}' (fail-open)",
                    formKey, role);
            return out;
        }
        for (Map<String, Object> rec : recs) {
            if (!formKey.equals(rec.get("recordKey"))) {
                continue;
            }
            if (rec.get("payload") instanceof Map<?, ?> payload
                    && payload.get("roles") instanceof Map<?, ?> roles
                    && roles.get(role) instanceof Map<?, ?> fields) {
                for (Map.Entry<?, ?> e : fields.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()).trim().toUpperCase());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Returns the allowed (writable) subset of the submitted fields for a form + role.
     * DEFAULT-PERMISSIVE: an unmapped form/role (or outage) returns the submitted map unchanged.
     * For a mapped role, each submitted field with:
     * <ul>
     *   <li>{@code WRITE} (or a field absent from the map) — kept;</li>
     *   <li>{@code READ} — stripped (read-only, not writable); no error;</li>
     *   <li>{@code HIDDEN} carrying a real (non-null, non-blank) value — {@link ApiException#forbiddenAutonomy}
     *       (an explicit write to a field the role may not see);</li>
     *   <li>{@code HIDDEN} carrying a null/blank value — stripped silently.</li>
     * </ul>
     */
    public Map<String, Object> enforce(String formKey, String role, Map<String, Object> submittedFields) {
        Map<String, Object> submitted = submittedFields == null ? Map.of() : submittedFields;
        Map<String, String> access = accessFor(formKey, role);
        if (access.isEmpty()) {
            return new LinkedHashMap<>(submitted);   // fail-open / unmapped -> full access
        }
        Map<String, Object> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : submitted.entrySet()) {
            String field = e.getKey();
            Object value = e.getValue();
            String level = access.getOrDefault(field, "WRITE");
            switch (level) {
                case "HIDDEN" -> {
                    boolean realWrite = value != null && !String.valueOf(value).trim().isBlank();
                    if (realWrite) {
                        throw ApiException.forbiddenAutonomy(
                                "Field '" + field + "' is hidden for role '" + role + "' on form '"
                                + formKey + "' and cannot be written");
                    }
                    // null/blank -> stripped (the field is not visible to this role)
                }
                case "READ" -> { /* read-only: stripped from the writable subset */ }
                default -> allowed.put(field, value);   // WRITE, or a field not in the map (permissive)
            }
        }
        return allowed;
    }

    // ---- TTL-cached snapshot (FieldPolicyService / ConfigValidator pattern) --------------------

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
                        .uri("/api/masters/FIELD_ACCESS")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                snapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("FIELD_ACCESS refresh failed ({}); serving stale snapshot", e.getMessage());
                    return s.records;
                }
                log.warn("FIELD_ACCESS fetch failed and no snapshot cached ({})", e.getMessage());
                return null;
            }
        }
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
