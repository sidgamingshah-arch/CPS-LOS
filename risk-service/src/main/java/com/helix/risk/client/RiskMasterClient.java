package com.helix.risk.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads master data from config-service for the risk engines. Today this serves
 * the FTP (funds-transfer-pricing) curve master; degrades gracefully to an empty
 * list so the pricing path falls back to the flat {@code cost_of_funds} from the
 * PRICING rule pack when config-service is unreachable.
 */
@Component
public class RiskMasterClient {

    private static final Logger log = LoggerFactory.getLogger(RiskMasterClient.class);

    private final RestClient client;

    public RiskMasterClient(@Value("${helix.config-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRecordDto(Long id, String masterType, String recordKey, String jurisdiction,
                                  Map<String, Object> payload, String status, int version) {
    }

    public List<MasterRecordDto> listActive(String type) {
        try {
            MasterRecordDto[] arr = client.get().uri("/api/masters/{t}", type).retrieve().body(MasterRecordDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config-service master {} unavailable ({}); FTP falls back to flat cost_of_funds",
                    type, e.getMessage());
            return List.of();
        }
    }
}
