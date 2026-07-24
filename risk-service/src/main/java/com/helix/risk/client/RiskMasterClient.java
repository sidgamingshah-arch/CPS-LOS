package com.helix.risk.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
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

    /** One value of a CODE_VALUE domain — {@code code} + optional numeric {@code score} (unknown fields ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeValueDto(String code, String label, Double score, Integer sortOrder) {
    }

    /** The active CODE_VALUE domain as served by config-service's flat {@code /api/code-values/{domain}} resolver. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodeValueSetDto(String domain, String label, List<CodeValueDto> values) {
    }

    /**
     * Reads a CODE_VALUE domain's per-code numeric {@code score} from config-service (e.g. the
     * OVERRIDE_ROLE master's role→notch-limit). Returns an EMPTY map on any failure or when the
     * domain has no scored values, so the caller applies its conservative built-in fallback —
     * this never blocks the deterministic figure/authority path. Codes are upper-cased for a
     * case-insensitive role match; scores are rounded to the nearest integer.
     */
    public Map<String, Integer> codeValueScores(String domain) {
        try {
            CodeValueSetDto set = client.get()
                    .uri("/api/code-values/{d}", domain)
                    .retrieve()
                    .body(CodeValueSetDto.class);
            if (set == null || set.values() == null) {
                return Map.of();
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            for (CodeValueDto cv : set.values()) {
                if (cv.code() != null && !cv.code().isBlank() && cv.score() != null) {
                    out.put(cv.code().toUpperCase(), (int) Math.round(cv.score()));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("config-service code-values {} unavailable ({}); caller uses built-in fallback",
                    domain, e.getMessage());
            return Map.of();
        }
    }
}
