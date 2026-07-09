package com.helix.origination.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Reads master data from config-service for origination engines (today: the
 * syndication fee schedule). Degrades gracefully — callers supply conservative
 * built-in defaults when the master is absent.
 */
@Component
public class OriginationMasterClient {

    private static final Logger log = LoggerFactory.getLogger(OriginationMasterClient.class);

    private final RestClient client;

    public OriginationMasterClient(@Value("${helix.config-service.base-url}") String baseUrl) {
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
            log.warn("config-service master {} unavailable ({}); using built-in defaults", type, e.getMessage());
            return List.of();
        }
    }

    /** Fee schedule for a jurisdiction, falling back to the default record, then built-ins. */
    public Map<String, Object> syndicationFees(String jurisdiction) {
        List<MasterRecordDto> all = listActive("SYNDICATION_FEE_MASTER");
        MasterRecordDto def = null, override = null;
        for (MasterRecordDto m : all) {
            String j = m.jurisdiction();
            if (jurisdiction != null && jurisdiction.equals(j)) override = m;
            else if (j == null || j.isBlank()) def = m;
        }
        MasterRecordDto pick = override != null ? override : def;
        if (pick != null && pick.payload() != null) return pick.payload();
        return Map.of("arrangementFeeBps", 75.0, "underwritingFeeBps", 25.0,
                "agencyFeeBps", 10.0, "participationFeeBps", 30.0);
    }
}
