package com.helix.risk.client;

import com.helix.risk.dto.RulePackDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches active, dual-signed rule packs from the config-service (the abstraction
 * layer). If config-service is unavailable, falls back to conservative built-in
 * defaults so the deterministic capital path still runs — graceful degradation
 * per the resilience NFR (PRD §9).
 */
@Component
public class ConfigClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigClient.class);

    private final RestClient client;

    public ConfigClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    public RulePackDto activePack(String jurisdiction, String type) {
        try {
            RulePackDto pack = client.get()
                    .uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction)
                            .queryParam("type", type)
                            .build())
                    .retrieve()
                    .body(RulePackDto.class);
            if (pack != null) {
                return pack;
            }
        } catch (Exception e) {
            log.warn("config-service unreachable for {}/{} ({}); using built-in fallback pack",
                    jurisdiction, type, e.getMessage());
        }
        return DefaultRulePacks.fallback(jurisdiction, type);
    }
}
