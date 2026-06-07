package com.helix.decision.client;

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

/**
 * Pulls authoritative figures from upstream services so approval routing uses
 * system-of-record data, never client-supplied values (PRD §8/§13 traceability).
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final RestClient config;
    private final RestClient counterparty;
    private final RestClient origination;
    private final RestClient risk;

    public UpstreamClient(@Value("${helix.config-service.base-url}") String configUrl,
                          @Value("${helix.counterparty-service.base-url}") String counterpartyUrl,
                          @Value("${helix.origination-service.base-url}") String originationUrl,
                          @Value("${helix.risk-service.base-url}") String riskUrl) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
        this.counterparty = RestClient.builder().baseUrl(counterpartyUrl).build();
        this.origination = RestClient.builder().baseUrl(originationUrl).build();
        this.risk = RestClient.builder().baseUrl(riskUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RulePackDto(String code, int version, Map<String, Object> payload) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreditInputsDto(String applicationReference, String counterpartyName, String jurisdiction,
                                  String segment, String facilityType, double requestedAmount, String currency,
                                  int tenorMonths, Map<String, Double> ratios) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RatingDto(String finalGrade, String modelGrade, double pd, boolean overridden,
                            boolean escalated, boolean confirmed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapitalDto(double rwa, double capitalRequired, String exposureClass) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingDto(double recommendedRate, double raroc, double hurdleRaroc, boolean belowHurdle) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskSummaryDto(String applicationReference, RatingDto rating, CapitalDto capital, PricingDto pricing) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FacilityViewDto(Long id, String reference, int ordinal, boolean primary, String facilityType,
                                  double amount, String currency, int tenorMonths, String purpose, Double indicativeRate,
                                  List<SublimitViewDto> sublimits, List<InterchangeabilityGroupViewDto> interchangeabilityGroups,
                                  double sublimitTotal, double sublimitHeadroom) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SublimitViewDto(Long id, Long facilityId, int ordinal, String code, String productType,
                                  double amount, String currency, Integer tenorMonths, String purpose,
                                  String interchangeableGroup, boolean fungible) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InterchangeabilityGroupViewDto(String groupKey, double combinedCap, String currency,
                                                 List<String> memberCodes, int memberCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollateralViewDto(Long id, Long facilityId, String collateralType, String description,
                                    double marketValue, double haircut, double effectiveValue,
                                    String perfectionStatus, String valuationDate, String owner) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DealEnvelopeDto(String applicationReference, String counterpartyName, String jurisdiction,
                                  String segment, double totalProposedAmount, String currency, int tenorMonths,
                                  List<FacilityViewDto> facilities, List<CollateralViewDto> collaterals,
                                  double totalCollateralCover, Map<String, Double> latestFinancials,
                                  Map<String, Double> ratios) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MasterRecordDto(Long id, String masterType, String recordKey, Map<String, Object> payload) {
    }

    /** Active master records of a type (e.g. CHECKLIST_MASTER) from config-service. */
    public List<MasterRecordDto> masters(String type) {
        try {
            MasterRecordDto[] arr = config.get().uri("/api/masters/{t}", type).retrieve().body(MasterRecordDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("config masters {} unavailable ({})", type, e.getMessage());
            return List.of();
        }
    }

    public RulePackDto doaMatrix(String jurisdiction) {
        try {
            return config.get().uri(uri -> uri.path("/api/rulepacks")
                            .queryParam("jurisdiction", jurisdiction)
                            .queryParam("type", "DOA_MATRIX").build())
                    .retrieve().body(RulePackDto.class);
        } catch (Exception e) {
            log.warn("config-service unreachable for DOA_MATRIX/{}; using fallback ({})", jurisdiction, e.getMessage());
            return fallbackDoa();
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

    public DealEnvelopeDto envelope(String reference) {
        try {
            return origination.get().uri("/api/applications/{ref}/envelope", reference)
                    .retrieve().body(DealEnvelopeDto.class);
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

    public RiskSummaryDto riskSummaryOrNull(String reference) {
        try {
            return risk.get().uri("/api/risk/{ref}", reference).retrieve().body(RiskSummaryDto.class);
        } catch (Exception e) {
            log.warn("risk-service summary unavailable for {} ({})", reference, e.getMessage());
            return null;
        }
    }

    public DealEnvelopeDto envelopeOrNull(String reference) {
        try {
            return origination.get().uri("/api/applications/{ref}/envelope", reference)
                    .retrieve().body(DealEnvelopeDto.class);
        } catch (Exception e) {
            log.warn("origination-service envelope unavailable for {} ({})", reference, e.getMessage());
            return null;
        }
    }

    // ---- group / counterparty / per-counterparty applications lookup ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CounterpartyGroupDto(Long id, String reference, String name, String groupRmId,
                                       String country, boolean multiCountry) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupMemberDto(String reference, String name, String recordType,
                                 String segment, String rm) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupExposureDto(CounterpartyGroupDto group, int memberCount, int obligorCount,
                                   List<GroupMemberDto> members, Map<String, Object> riskFlags) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanApplicationRefDto(String reference, String counterpartyRef,
                                        String counterpartyName, String status) {
    }

    public CounterpartyGroupDto groupByReference(String groupReference) {
        try {
            return counterparty.get().uri("/api/initiation/groups/by-reference/{r}", groupReference)
                    .retrieve().body(CounterpartyGroupDto.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "counterparty-service unavailable for group " + groupReference + ": " + e.getMessage());
        }
    }

    public GroupExposureDto groupExposure(String groupReference) {
        try {
            return counterparty.get()
                    .uri("/api/initiation/groups/by-reference/{r}/exposure", groupReference)
                    .retrieve().body(GroupExposureDto.class);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "counterparty-service group exposure unavailable: " + e.getMessage());
        }
    }

    public List<LoanApplicationRefDto> applicationsForCounterparty(String counterpartyRef) {
        try {
            LoanApplicationRefDto[] arr = origination.get()
                    .uri("/api/applications/by-counterparty/{r}", counterpartyRef)
                    .retrieve().body(LoanApplicationRefDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("origination-service applications-by-counterparty unavailable for {} ({})",
                    counterpartyRef, e.getMessage());
            return List.of();
        }
    }

    private RulePackDto fallbackDoa() {
        Map<String, Object> payload = Map.of(
                "levels", List.of(
                        Map.of("max_amount", 50_000_000d, "min_grade", "BBB", "authority", "RM_HEAD"),
                        Map.of("max_amount", 250_000_000d, "min_grade", "BB", "authority", "CREDIT_OFFICER"),
                        Map.of("max_amount", 1_000_000_000d, "min_grade", "B", "authority", "CREDIT_COMMITTEE"),
                        Map.of("max_amount", Double.MAX_VALUE, "min_grade", "D", "authority", "BOARD_COMMITTEE")),
                "deviation_escalates_one_level", true,
                "below_hurdle_requires_escalation", true);
        return new RulePackDto("fallback_doa_matrix", 0, payload);
    }
}
