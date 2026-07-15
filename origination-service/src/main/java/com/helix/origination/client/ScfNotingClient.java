package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Best-effort hook into decision-service's governed Noting engine. When an SCF programme
 * is submitted for approval we raise a linked {@code PRODUCT_PAPER} noting (the Indian-bank
 * decision RECORD for a product paper) and store its {@code notingRef} on the programme.
 * This is strictly best-effort: a decision-service outage must NOT fail SCF submit/approval —
 * the call returns {@code null} on any failure and the caller logs + continues. The noting
 * is a record; it never mutates an authoritative figure.
 */
@Component
public class ScfNotingClient {

    private static final Logger log = LoggerFactory.getLogger(ScfNotingClient.class);

    private final RestClient client;

    public ScfNotingClient(@Value("${helix.decision-service.base-url:http://localhost:8085}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NotingDto(String notingRef, String status) {
    }

    /**
     * Create a PRODUCT_PAPER noting for the programme. Returns the created noting reference,
     * or {@code null} if decision-service is unreachable / rejects the request.
     */
    public String createProductPaperNoting(String scfRef, String title, String narrative,
                                           Map<String, Object> payload, String actor) {
        try {
            NotingDto n = client.post().uri("/api/notings")
                    .header("X-Actor", actor == null || actor.isBlank() ? "rm.user" : actor)
                    .body(Map.of(
                            "notingType", "PRODUCT_PAPER",
                            "subjectType", "ScfProgram",
                            "subjectRef", scfRef,
                            "title", title,
                            "narrative", narrative == null ? "" : narrative,
                            "payload", payload == null ? Map.of() : payload))
                    .retrieve().body(NotingDto.class);
            return n == null ? null : n.notingRef();
        } catch (Exception e) {
            log.warn("decision-service noting creation unavailable for SCF {} ({}) — SCF continues without a linked noting",
                    scfRef, e.getMessage());
            return null;
        }
    }
}
