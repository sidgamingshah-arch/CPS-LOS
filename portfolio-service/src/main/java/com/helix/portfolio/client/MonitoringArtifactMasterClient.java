package com.helix.portfolio.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code MONITORING_ARTIFACT_TYPE} + {@code VENDOR_MASTER} masters from
 * config-service (config-as-data — a new artifact type is a new master row, no code
 * change). Every read is best-effort with a conservative fallback: if config-service
 * is unreachable (or the type is unseeded) the artifact still materialises with a
 * single {@code notes} section, {@code requiresAuthorize=false}, {@code vendorRfq=false}.
 */
@Component
public class MonitoringArtifactMasterClient {

    private static final Logger log = LoggerFactory.getLogger(MonitoringArtifactMasterClient.class);
    private static final String TYPE_MASTER = "MONITORING_ARTIFACT_TYPE";
    private static final String VENDOR_MASTER = "VENDOR_MASTER";

    private final RestClient config;

    public MonitoringArtifactMasterClient(@Value("${helix.config-service.base-url}") String configUrl) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
    }

    /** One template section (materialised into the artifact's section map at create-time). */
    public record Section(String key, String label) {
    }

    /** Resolved artifact-type spec: which sections, whether it authorises, whether it RFQs a vendor. */
    public record ArtifactTypeSpec(int version, List<Section> sections, boolean requiresAuthorize,
                                   boolean vendorRfq) {
    }

    private static ArtifactTypeSpec fallback() {
        return new ArtifactTypeSpec(0, List.of(new Section("notes", "Notes")), false, false);
    }

    @SuppressWarnings("unchecked")
    public ArtifactTypeSpec artifactType(String key) {
        try {
            Map<String, Object> rec = config.get()
                    .uri("/api/masters/{type}/{key}", TYPE_MASTER, key)
                    .retrieve().body(Map.class);
            if (rec == null) {
                return fallback();
            }
            int version = rec.get("version") instanceof Number n ? n.intValue() : 0;
            Map<String, Object> payload = rec.get("payload") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            List<Section> sections = new ArrayList<>();
            if (payload.get("sections") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object sk = m.get("key");
                        Object sl = m.get("label");
                        if (sk != null) {
                            sections.add(new Section(String.valueOf(sk),
                                    sl == null ? String.valueOf(sk) : String.valueOf(sl)));
                        }
                    }
                }
            }
            if (sections.isEmpty()) {
                sections = List.of(new Section("notes", "Notes"));
            }
            boolean requiresAuthorize = truthy(payload.get("requiresAuthorize"));
            boolean vendorRfq = truthy(payload.get("vendorRfq"));
            return new ArtifactTypeSpec(version, sections, requiresAuthorize, vendorRfq);
        } catch (Exception e) {
            log.warn("MONITORING_ARTIFACT_TYPE master unreachable for '{}'; using conservative fallback ({})",
                    key, e.getMessage());
            return fallback();
        }
    }

    /** Active VENDOR_MASTER record keys; empty on failure (validation then degrades open). */
    @SuppressWarnings("unchecked")
    public List<String> vendorKeys() {
        List<String> out = new ArrayList<>();
        try {
            List<Map<String, Object>> recs = config.get()
                    .uri("/api/masters/{type}", VENDOR_MASTER)
                    .retrieve().body(List.class);
            if (recs != null) {
                for (Map<String, Object> r : recs) {
                    Object key = r.get("recordKey");
                    if (key != null) out.add(String.valueOf(key));
                }
            }
        } catch (Exception e) {
            log.warn("VENDOR_MASTER unreachable; vendor validation degrades open ({})", e.getMessage());
        }
        return out;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }
}
