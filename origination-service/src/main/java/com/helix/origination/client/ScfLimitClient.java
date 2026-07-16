package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Best-effort hook into limit-service's own governed limit-tree API. When an SCF programme
 * is APPROVED we register its {@code programLimit} as a root limit node against the anchor
 * (via {@code POST /api/limits/root}). The registration goes through limit-service's own
 * governed API — SCF never writes an authoritative limit figure itself. The call is
 * best-effort and environment-dependent: on any failure it returns {@code null} and the
 * caller logs + continues, so a limit-service outage never fails SCF approval.
 */
@Component
public class ScfLimitClient {

    private static final Logger log = LoggerFactory.getLogger(ScfLimitClient.class);

    private final RestClient client;

    public ScfLimitClient(@Value("${helix.limit-service.base-url:http://localhost:8088}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LimitNodeDto(Long id, String reference, String code) {
    }

    /**
     * Register the programme limit as a root node. Returns the created node reference, or
     * {@code null} if limit-service is unreachable / rejects the request.
     */
    public String registerProgramLimit(String anchorRef, String scfRef, double programLimit,
                                        String currency, String actor) {
        try {
            LimitNodeDto node = client.post().uri("/api/limits/root")
                    .header("X-Actor", actor == null || actor.isBlank() ? "credit.ops" : actor)
                    .body(Map.of(
                            "cif", anchorRef,
                            "applicationRef", scfRef,
                            "code", scfRef,
                            "sanctionedAmount", programLimit,
                            "currency", currency == null || currency.isBlank() ? "INR" : currency,
                            "fungible", false))
                    .retrieve().body(LimitNodeDto.class);
            return node == null ? null : node.reference();
        } catch (Exception e) {
            log.warn("limit-service registration unavailable for SCF {} ({}) — approval stands without a limit node",
                    scfRef, e.getMessage());
            return null;
        }
    }
}
