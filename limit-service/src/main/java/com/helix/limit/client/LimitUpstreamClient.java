package com.helix.limit.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Reads the deal's facility/sub-limit structure and exposure norms to build the limit tree. */
@Component
public class LimitUpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(LimitUpstreamClient.class);

    private final RestClient origination;
    private final RestClient config;

    public LimitUpstreamClient(@Value("${helix.origination-service.base-url}") String originationUrl,
                               @Value("${helix.config-service.base-url}") String configUrl) {
        this.origination = RestClient.builder().baseUrl(originationUrl).build();
        this.config = RestClient.builder().baseUrl(configUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SublimitDto(Long id, int ordinal, String code, String productType, double amount,
                              String currency, Integer tenorMonths, String purpose,
                              String interchangeableGroup, boolean fungible) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FacilityDto(Long id, String reference, int ordinal, boolean primary, String facilityType,
                              double amount, String currency, int tenorMonths, String purpose, Double indicativeRate,
                              List<SublimitDto> sublimits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditInputsDto(String applicationReference, String counterpartyRef, String counterpartyName,
                                  String jurisdiction, String segment, String currency) {
    }

    public List<FacilityDto> facilities(String reference) {
        try {
            FacilityDto[] arr = origination.get().uri("/api/applications/{r}/facilities/view", reference)
                    .retrieve().body(FacilityDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "origination-service unavailable: " + e.getMessage());
        }
    }

    public CreditInputsDto creditInputs(String reference) {
        try {
            return origination.get().uri("/api/applications/{r}/credit-inputs", reference)
                    .retrieve().body(CreditInputsDto.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "origination-service unavailable: " + e.getMessage());
        }
    }

    /** Exposure-norm percentages + capital base from the active rule pack (with fallback). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exposureNorms(String jurisdiction) {
        try {
            Map<String, Object> pack = config.get().uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction).queryParam("type", "EXPOSURE_LIMITS").build())
                    .retrieve().body(Map.class);
            if (pack != null && pack.get("payload") instanceof Map<?, ?> p) {
                return (Map<String, Object>) p;
            }
        } catch (Exception e) {
            log.warn("config EXPOSURE_LIMITS/{} unavailable ({}); using fallback", jurisdiction, e.getMessage());
        }
        return Map.of("single_name_pct_capital", 0.15, "connected_group_pct_capital", 0.25,
                "sector_cap_pct_portfolio", 0.20, "geography_cap_pct_portfolio", 0.30,
                "capital_base", 50_000_000_000d);
    }
}
