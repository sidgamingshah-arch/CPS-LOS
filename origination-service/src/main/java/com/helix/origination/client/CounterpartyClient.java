package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the borrower's sector from counterparty-service so the application can pin
 * it at create time. Sector drives sector-specific financial / projection / scoring
 * templates downstream. Best-effort: returns null when unavailable so application
 * creation never hard-fails on a counterparty-service hiccup.
 */
@Component
public class CounterpartyClient {

    private static final Logger log = LoggerFactory.getLogger(CounterpartyClient.class);

    private final RestClient client;

    public CounterpartyClient(@Value("${helix.counterparty-service.base-url:http://localhost:8082}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CounterpartyDto(String reference, String sector, String segment) { }

    public String sectorFor(String counterpartyRef) {
        if (counterpartyRef == null || counterpartyRef.isBlank()) return null;
        try {
            CounterpartyDto cp = client.get().uri("/api/counterparties/by-reference/{r}", counterpartyRef)
                    .retrieve().body(CounterpartyDto.class);
            return cp == null ? null : cp.sector();
        } catch (Exception e) {
            log.warn("counterparty sector lookup unavailable for {} ({})", counterpartyRef, e.getMessage());
            return null;
        }
    }
}
