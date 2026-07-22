package com.helix.decision.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the {@code ANNEXURE_TYPE} master from config-service (config-as-data — a new
 * annexure type is a new master row, no code change). Every read is best-effort with a
 * conservative fallback: if config-service is unreachable (or the type is unseeded) the
 * annexure still materialises with a single {@code summary} section at version 0.
 *
 * <p>The master payload carries {@code sections: [{key, title}]}; the resolved
 * {@link AnnexureTypeSpec} pins the master {@code version} so a consumer can record which
 * version it materialised (later master edits never reshape an in-flight annexure).</p>
 */
@Component
public class AnnexureTypeMasterClient {

    private static final Logger log = LoggerFactory.getLogger(AnnexureTypeMasterClient.class);
    private static final String TYPE_MASTER = "ANNEXURE_TYPE";

    private final RestClient config;

    public AnnexureTypeMasterClient(@Value("${helix.config-service.base-url}") String configUrl) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
    }

    /** One template section (materialised into the annexure's section map at create-time). */
    public record Section(String key, String title) {
    }

    /** Resolved annexure-type spec: the pinned master version + the ordered section template. */
    public record AnnexureTypeSpec(int version, List<Section> sections) {
    }

    private static AnnexureTypeSpec fallback() {
        return new AnnexureTypeSpec(0, List.of(new Section("summary", "Summary")));
    }

    @SuppressWarnings("unchecked")
    public AnnexureTypeSpec annexureType(String key) {
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
                        Object st = m.get("title");
                        if (sk != null) {
                            sections.add(new Section(String.valueOf(sk),
                                    st == null ? String.valueOf(sk) : String.valueOf(st)));
                        }
                    }
                }
            }
            if (sections.isEmpty()) {
                sections = List.of(new Section("summary", "Summary"));
            }
            return new AnnexureTypeSpec(version, sections);
        } catch (Exception e) {
            log.warn("ANNEXURE_TYPE master unreachable for '{}'; using conservative fallback ({})",
                    key, e.getMessage());
            return fallback();
        }
    }
}
