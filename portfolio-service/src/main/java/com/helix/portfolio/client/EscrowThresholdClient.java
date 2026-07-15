package com.helix.portfolio.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Reads the escrow budget-vs-actual RAG thresholds from the {@code VALIDATION_PARAMETER}
 * master in config-service (config-as-data — tune the amber/red utilisation bands with a
 * master edit, never a code change). The domain key is {@code ESCROW_UTILISATION}; the
 * payload carries {@code amberUtilisationPct} and {@code redUtilisationPct} (percent, 0–100).
 *
 * <p>Every read is best-effort with a <b>conservative</b> fallback: if config-service is
 * unreachable (or the domain is unseeded) we alert earlier — amber at 75%, red at 90% —
 * so a threshold outage never hides a potential escrow overspend.</p>
 */
@Component
public class EscrowThresholdClient {

    private static final Logger log = LoggerFactory.getLogger(EscrowThresholdClient.class);
    private static final String MASTER = "VALIDATION_PARAMETER";
    private static final String DOMAIN = "ESCROW_UTILISATION";

    /** Conservative defaults — flag risk sooner when the master is unavailable. */
    private static final double FALLBACK_AMBER = 75.0;
    private static final double FALLBACK_RED = 90.0;

    private final RestClient config;

    public EscrowThresholdClient(@Value("${helix.config-service.base-url}") String configUrl) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
    }

    /**
     * Resolved RAG bands (percent utilisation). {@code source} records provenance —
     * {@code MASTER} when read from config, {@code FALLBACK} when the conservative
     * defaults were applied.
     */
    public record RagThresholds(double amberPct, double redPct, String source) {
    }

    private static RagThresholds fallback() {
        return new RagThresholds(FALLBACK_AMBER, FALLBACK_RED, "FALLBACK");
    }

    @SuppressWarnings("unchecked")
    public RagThresholds thresholds() {
        try {
            Map<String, Object> rec = config.get()
                    .uri("/api/masters/{type}/{key}", MASTER, DOMAIN)
                    .retrieve().body(Map.class);
            if (rec == null) {
                return fallback();
            }
            Map<String, Object> payload = rec.get("payload") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            double amber = num(payload.get("amberUtilisationPct"), FALLBACK_AMBER);
            double red = num(payload.get("redUtilisationPct"), FALLBACK_RED);
            // Guard against an inverted/degenerate config: red must sit at/above amber.
            if (red < amber) {
                log.warn("ESCROW_UTILISATION thresholds inverted (amber={} > red={}); using conservative fallback",
                        amber, red);
                return fallback();
            }
            return new RagThresholds(amber, red, "MASTER");
        } catch (Exception e) {
            log.warn("VALIDATION_PARAMETER/{} unreachable; using conservative RAG fallback ({})",
                    DOMAIN, e.getMessage());
            return fallback();
        }
    }

    private static double num(Object v, double fallback) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
