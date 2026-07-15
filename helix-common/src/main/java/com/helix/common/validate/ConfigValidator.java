package com.helix.common.validate;

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
 * Config-driven field validation engine. Rules live in the {@code VALIDATION_PARAMETER}
 * master in config-service (recordKey = domain key, payload
 * {@code rules: [{field, type, param, severity}]}) so a bank re-authors formats under
 * maker-checker instead of a code change. Rule types: {@code REGEX} (param = pattern),
 * {@code RANGE} (param = "min..max", either bound optional), {@code REQUIRED}, and
 * {@code CHECKSUM} (param names a registered algorithm — {@code LEI} ISO 17442 mod-97,
 * {@code GSTIN} standard base-36 alternating-weight check digit, {@code PAN} format-only).
 *
 * <p>REGEX / RANGE / CHECKSUM rules apply to present-and-non-blank fields only — absent
 * identifiers are optional and skip. {@code REQUIRED} is the one rule that fails on absence.
 * ERROR failures aggregate into a single {@link ApiException#badRequest} listing every
 * failing field; WARN failures are returned to the caller as advisory messages.</p>
 *
 * <p><b>Caching &amp; outage behaviour</b> mirrors
 * {@link com.helix.common.rbac.ActorDirectory}: one snapshot fetch of the full
 * VALIDATION_PARAMETER list per TTL window (lock-guarded), stale-served on refresh
 * failure. On a cold-start outage (no snapshot ever fetched) validation is SKIPPED with
 * a WARN — business operations must never block on a config-service outage.</p>
 */
@Component
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);
    private static final String B36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final RestClient client;
    private final long ttlSeconds;
    private final Object refreshLock = new Object();
    private volatile Snapshot snapshot;   // null until the first successful fetch

    public ConfigValidator(@Value("${helix.config-service.base-url}") String baseUrl,
                           @Value("${helix.validation.cache-ttl-seconds:5}") long ttlSeconds) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.ttlSeconds = ttlSeconds;
    }

    /** One failed rule: which field, which rule type, ERROR or WARN, and a human-readable message. */
    public record RuleFailure(String field, String type, String severity, String message) {

        public boolean isError() {
            return !"WARN".equalsIgnoreCase(severity);
        }
    }

    /**
     * Applies the domain's rules to the supplied fields. Throws a single 400 aggregating
     * every ERROR failure; returns the WARN failure messages (advisory, caller may log or
     * surface them). Returns an empty list — validation skipped — when config-service has
     * never been reachable (fail-open).
     */
    public List<String> validate(String domainKey, Map<String, Object> fields) {
        List<RuleFailure> failures = evaluate(domainKey, fields);
        List<String> errors = failures.stream().filter(RuleFailure::isError).map(RuleFailure::message).toList();
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(
                    "Validation failed [" + domainKey + "]: " + String.join("; ", errors));
        }
        return failures.stream().map(RuleFailure::message).toList();
    }

    /**
     * Non-throwing core: every rule failure (ERROR and WARN) for the supplied fields.
     * Empty when the domain has no rules or when config-service has never been reachable
     * (fail-open, logged) — never throws on a config outage.
     */
    public List<RuleFailure> evaluate(String domainKey, Map<String, Object> fields) {
        List<Map<String, Object>> recs = records();
        if (recs == null) {
            log.warn("VALIDATION_PARAMETER unavailable and no snapshot cached — skipping validation for '{}' (fail-open)",
                    domainKey);
            return List.of();
        }
        List<RuleFailure> failures = new ArrayList<>();
        for (Map<String, Object> rec : recs) {
            if (!domainKey.equals(rec.get("recordKey"))) {
                continue;
            }
            if (rec.get("payload") instanceof Map<?, ?> payload
                    && payload.get("rules") instanceof List<?> rules) {
                for (Object r : rules) {
                    if (r instanceof Map<?, ?> rule) {
                        applyRule(rule, fields, failures);
                    }
                }
            }
        }
        return failures;
    }

    private void applyRule(Map<?, ?> rule, Map<String, Object> fields, List<RuleFailure> failures) {
        String field = str(rule.get("field"));
        String type = str(rule.get("type")).toUpperCase();
        String param = str(rule.get("param"));
        String severity = rule.get("severity") == null ? "ERROR" : str(rule.get("severity")).toUpperCase();
        Object raw = fields.get(field);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        boolean present = !value.isBlank();

        switch (type) {
            case "REQUIRED" -> {
                if (!present) {
                    failures.add(new RuleFailure(field, type, severity, field + " is required"));
                }
            }
            case "REGEX" -> {
                if (present && !value.matches(param)) {
                    failures.add(new RuleFailure(field, type, severity,
                            field + " '" + value + "' does not match the required format"));
                }
            }
            case "RANGE" -> {
                if (present) {
                    checkRange(field, value, param, severity, failures);
                }
            }
            case "CHECKSUM" -> {
                if (present && !checksumValid(param, value)) {
                    failures.add(new RuleFailure(field, type, severity,
                            field + " '" + value + "' fails the " + param.toUpperCase() + " checksum/format"));
                }
            }
            default -> log.warn("Unknown VALIDATION_PARAMETER rule type '{}' for field '{}' — skipped", type, field);
        }
    }

    /** RANGE param is "min..max"; either bound may be omitted ("1.." / "..100"). Non-numeric values fail. */
    private void checkRange(String field, String value, String param, String severity, List<RuleFailure> failures) {
        double v;
        try {
            v = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            failures.add(new RuleFailure(field, "RANGE", severity, field + " '" + value + "' is not numeric"));
            return;
        }
        int sep = param.indexOf("..");
        if (sep < 0) {
            log.warn("Malformed RANGE param '{}' for field '{}' — expected 'min..max'; rule skipped", param, field);
            return;
        }
        String lo = param.substring(0, sep).trim();
        String hi = param.substring(sep + 2).trim();
        try {
            if ((!lo.isEmpty() && v < Double.parseDouble(lo)) || (!hi.isEmpty() && v > Double.parseDouble(hi))) {
                failures.add(new RuleFailure(field, "RANGE", severity,
                        field + " " + value + " is outside the allowed range " + param));
            }
        } catch (NumberFormatException e) {
            log.warn("Malformed RANGE bounds '{}' for field '{}' — rule skipped", param, field);
        }
    }

    // ---- checksum registry -------------------------------------------------------------------

    private boolean checksumValid(String algorithm, String value) {
        return switch (algorithm.toUpperCase()) {
            case "LEI" -> leiValid(value);
            case "GSTIN" -> gstinValid(value);
            case "PAN" -> value.matches("[A-Z]{5}[0-9]{4}[A-Z]");   // format-only: PAN has no public check digit
            default -> {
                log.warn("Unknown CHECKSUM algorithm '{}' — rule skipped (fail-open)", algorithm);
                yield true;
            }
        };
    }

    /** ISO 17442: 18 alphanumerics + 2 check digits; letters→numbers (A=10..Z=35), whole string mod 97 == 1. */
    static boolean leiValid(String lei) {
        if (!lei.matches("[A-Z0-9]{18}[0-9]{2}")) {
            return false;
        }
        int mod = 0;
        for (char c : lei.toCharArray()) {
            int v = Character.digit(c, 36);
            mod = (v < 10 ? mod * 10 + v : mod * 100 + v) % 97;
        }
        return mod == 1;
    }

    /**
     * Standard GSTIN check digit: base-36 values of the first 14 characters, alternating
     * weights 1/2 (weight 1 at the first character), each product folded as
     * {@code p/36 + p%36}; check char = base36[(36 - sum%36) % 36].
     */
    static boolean gstinValid(String gstin) {
        if (!gstin.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int v = B36.indexOf(gstin.charAt(i));
            int p = v * (i % 2 == 0 ? 1 : 2);
            sum += p / 36 + p % 36;
        }
        return gstin.charAt(14) == B36.charAt((36 - sum % 36) % 36);
    }

    // ---- TTL-cached snapshot (ActorDirectory pattern) ------------------------------------------

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
                        .uri("/api/masters/VALIDATION_PARAMETER")
                        .retrieve()
                        .body(List.class);
                Snapshot fresh = new Snapshot(recs == null ? List.of() : recs,
                        Instant.now().plusSeconds(ttlSeconds));
                snapshot = fresh;
                return fresh.records;
            } catch (Exception e) {
                if (s != null) {
                    log.warn("VALIDATION_PARAMETER refresh failed ({}); serving stale snapshot", e.getMessage());
                    return s.records;
                }
                log.warn("VALIDATION_PARAMETER fetch failed and no snapshot cached ({})", e.getMessage());
                return null;
            }
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private record Snapshot(List<Map<String, Object>> records, Instant until) { }
}
