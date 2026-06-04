package com.helix.portfolio.client;

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

/** Pulls deal facts, rule packs and covenants from upstream services for portfolio analytics. */
@Component
public class PortfolioUpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(PortfolioUpstreamClient.class);

    private final RestClient config;
    private final RestClient origination;
    private final RestClient risk;
    private final RestClient decision;

    public PortfolioUpstreamClient(@Value("${helix.config-service.base-url}") String configUrl,
                                   @Value("${helix.origination-service.base-url}") String originationUrl,
                                   @Value("${helix.risk-service.base-url}") String riskUrl,
                                   @Value("${helix.decision-service.base-url}") String decisionUrl) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
        this.origination = RestClient.builder().baseUrl(originationUrl).build();
        this.risk = RestClient.builder().baseUrl(riskUrl).build();
        this.decision = RestClient.builder().baseUrl(decisionUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RulePackDto(String code, int version, Map<String, Object> payload) {
        public double number(String key, double fallback) {
            Object v = payload == null ? null : payload.get(key);
            return v instanceof Number n ? n.doubleValue() : fallback;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String key) {
            Object v = payload == null ? null : payload.get(key);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditInputsDto(String applicationReference, String counterpartyRef, String counterpartyName,
                                  String jurisdiction, String segment, String facilityType, double requestedAmount,
                                  String currency, Map<String, Double> ratios, Map<String, Double> latestFinancials) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingDto(String finalGrade, double pd, double lgd, double ead) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapitalDto(double rwa, double capitalRequired, String exposureClass) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingDto(double recommendedRate, double raroc, double hurdleRaroc, double expectedLoss,
                             double capitalCharge, double costOfFundsAmount, double opexAmount, double ead,
                             boolean belowHurdle) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskSummaryDto(RatingDto rating, CapitalDto capital, PricingDto pricing) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CovenantDto(Long id, String metric, String operator, double threshold, String breachSeverity,
                              List<String> onBreach, boolean active) {
    }

    public RulePackDto pack(String jurisdiction, String type, RulePackDto fallback) {
        try {
            RulePackDto p = config.get().uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction).queryParam("type", type).build())
                    .retrieve().body(RulePackDto.class);
            return p != null ? p : fallback;
        } catch (Exception e) {
            log.warn("config-service unreachable for {}/{}; using fallback ({})", type, jurisdiction, e.getMessage());
            return fallback;
        }
    }

    public CreditInputsDto creditInputs(String reference) {
        try {
            return origination.get().uri("/api/applications/{ref}/credit-inputs", reference)
                    .retrieve().body(CreditInputsDto.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "origination-service unavailable: " + e.getMessage());
        }
    }

    public RiskSummaryDto riskSummary(String reference) {
        try {
            return risk.get().uri("/api/risk/{ref}", reference).retrieve().body(RiskSummaryDto.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "risk-service unavailable: " + e.getMessage());
        }
    }

    public List<CovenantDto> covenants(String reference) {
        try {
            CovenantDto[] arr = decision.get().uri("/api/decisions/{ref}/covenants", reference)
                    .retrieve().body(CovenantDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("decision-service unreachable for covenants/{} ({})", reference, e.getMessage());
            return List.of();
        }
    }

    /** Generic GET wrapper so portfolio aggregations can pull from any upstream by URL. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String svc, String uri, Object... vars) {
        RestClient c = switch (svc) {
            case "origination" -> origination;
            case "risk" -> risk;
            case "decision" -> decision;
            case "config" -> config;
            default -> null;
        };
        if (c == null) return Map.of();
        try {
            return c.get().uri(uri, vars).retrieve().body(Map.class);
        } catch (Exception e) {
            log.warn("upstream {} {} unavailable ({})", svc, uri, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getList(String svc, String uri, Object... vars) {
        RestClient c = switch (svc) {
            case "origination" -> origination;
            case "risk" -> risk;
            case "decision" -> decision;
            case "config" -> config;
            default -> null;
        };
        if (c == null) return List.of();
        try {
            List<?> raw = c.get().uri(uri, vars).retrieve().body(List.class);
            return raw == null ? List.of() : (List<Map<String, Object>>) raw;
        } catch (Exception e) {
            log.warn("upstream {} {} unavailable ({})", svc, uri, e.getMessage());
            return List.of();
        }
    }
}
