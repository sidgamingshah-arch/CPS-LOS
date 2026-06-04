package com.helix.counterparty.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads master data from config-service (dedup rules, negative list, thresholds).
 * Degrades gracefully to conservative built-ins if config-service is unavailable.
 */
@Component
public class ConfigMasterClient {

    private static final Logger log = LoggerFactory.getLogger(ConfigMasterClient.class);

    private final RestClient client;

    public ConfigMasterClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRecordDto(Long id, String masterType, String recordKey, String jurisdiction,
                                  Map<String, Object> payload, String status, int version) {
    }

    @SuppressWarnings("unchecked")
    public List<MasterRecordDto> listActive(String type) {
        try {
            MasterRecordDto[] arr = client.get().uri("/api/masters/{t}", type).retrieve().body(MasterRecordDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config-service master {} unavailable ({})", type, e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> dedupRules() {
        return listActive("DEDUP_RULES").stream().findFirst()
                .map(MasterRecordDto::payload)
                .orElse(Map.of("strategy", "NAME_AND_IDENTIFIER",
                        "identifierFields", List.of("registrationNo"), "nameMatchThreshold", 0.82));
    }

    public List<MasterRecordDto> negativeList() {
        return listActive("NEGATIVE_LIST");
    }

    public int draftCleanupMonths() {
        return listActive("DRAFT_CLEANUP").stream().findFirst()
                .map(r -> ((Number) r.payload().getOrDefault("months", 6)).intValue())
                .orElse(6);
    }

    public int inactivityDays() {
        return listActive("INACTIVITY_THRESHOLD").stream().findFirst()
                .map(r -> ((Number) r.payload().getOrDefault("days", 90)).intValue())
                .orElse(90);
    }
}
