package com.helix.decision.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
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
    private final AuditService audit;

    public UpstreamClient(@Value("${helix.config-service.base-url}") String configUrl,
                          @Value("${helix.counterparty-service.base-url}") String counterpartyUrl,
                          @Value("${helix.origination-service.base-url}") String originationUrl,
                          @Value("${helix.risk-service.base-url}") String riskUrl,
                          AuditService audit) {
        this.config = RestClient.builder().baseUrl(configUrl).build();
        this.counterparty = RestClient.builder().baseUrl(counterpartyUrl).build();
        this.origination = RestClient.builder().baseUrl(originationUrl).build();
        this.risk = RestClient.builder().baseUrl(riskUrl).build();
        this.audit = audit;
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
    public record MasterRecordDto(Long id, String masterType, String recordKey, String jurisdiction,
                                  Map<String, Object> payload) {
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

    /**
     * Best-effort syndication agency allocation on drawdown release. If the deal is
     * a syndication, the agent allocates the funded draw pro-rata across lenders.
     * Idempotent on {@code drawdownRef}, so this never double-counts.
     *
     * <p>Outcomes:
     * <ul>
     *   <li>200 — allocated (or reused an existing allocation): silent success.</li>
     *   <li>400/404 — not a syndication deal: legitimate skip, logged at DEBUG, no audit.</li>
     *   <li>Anything else (5xx, network, 4xx other than the two above) — origination
     *       briefly broken on a deal that <em>is</em> a syndication: we log a WARN and
     *       stamp a {@code SYNDICATION_ALLOCATION_SKIPPED} audit event so the agency
     *       desk has a discoverable trail and can re-trigger via {@code /allocate}.</li>
     * </ul>
     * The release path itself is never blocked: the limit booking has already succeeded
     * and the agency reconciliation is a downstream concern, not a money-movement.</p>
     */
    public void allocateSyndicationOrSkip(String reference, String drawdownRef, double amount,
                                          String currency, String actor) {
        try {
            origination.post().uri("/api/syndication/{ref}/allocate", reference)
                    .header("X-Actor", actor == null ? "agency.desk" : actor)
                    .body(Map.of("drawdownRef", drawdownRef, "amount", amount,
                            "currency", currency == null ? "" : currency))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.NotFound expected) {
            // Not a syndication deal — the standalone /allocate endpoint rejects
            // with 400/404 in that case. Truly silent skip; no audit.
            log.debug("syndication allocate skipped for {} draw {} (not a syndication: {})",
                    reference, drawdownRef, expected.getMessage());
        } catch (Exception e) {
            // Origination is reachable-but-broken, or the deal IS a syndication and
            // something further down threw. Surface it.
            log.warn("syndication allocate FAILED for {} draw {} — agency desk must re-trigger ({})",
                    reference, drawdownRef, e.getMessage());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("drawdownRef", drawdownRef);
            details.put("amount", amount);
            details.put("currency", currency == null ? "" : currency);
            details.put("error", e.getMessage());
            try {
                audit.engine("SYNDICATION_ALLOCATION_SKIPPED", "Application", reference,
                        "Syndication allocation failed on release of " + drawdownRef
                                + " — agency desk must re-trigger /allocate (" + e.getMessage() + ")",
                        details);
            } catch (Exception auditErr) {
                log.warn("could not stamp SYNDICATION_ALLOCATION_SKIPPED audit ({})", auditErr.getMessage());
            }
        }
    }

    /**
     * Best-effort syndication allocation reversal when a released drawdown is
     * reversed. Same outcome semantics as {@link #allocateSyndicationOrSkip}:
     * 400/404 (not a syndication) is a silent skip; any other failure is WARN +
     * audited so the agency desk can re-trigger.
     */
    public void reverseSyndicationOrSkip(String reference, String drawdownRef, String actor) {
        try {
            origination.post().uri("/api/syndication/{ref}/allocations/reverse", reference)
                    .header("X-Actor", actor == null ? "agency.desk" : actor)
                    .body(Map.of("drawdownRef", drawdownRef))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.NotFound expected) {
            log.debug("syndication reverse skipped for {} draw {} (not a syndication: {})",
                    reference, drawdownRef, expected.getMessage());
        } catch (Exception e) {
            log.warn("syndication reverse FAILED for {} draw {} — agency desk must re-trigger ({})",
                    reference, drawdownRef, e.getMessage());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("drawdownRef", drawdownRef);
            details.put("error", e.getMessage());
            try {
                audit.engine("SYNDICATION_REVERSAL_SKIPPED", "Application", reference,
                        "Syndication allocation reversal failed for " + drawdownRef
                                + " — agency desk must re-trigger (" + e.getMessage() + ")",
                        details);
            } catch (Exception auditErr) {
                log.warn("could not stamp SYNDICATION_REVERSAL_SKIPPED audit ({})", auditErr.getMessage());
            }
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
                                        String counterpartyName, String status,
                                        String createdAt) {
    }

    public CounterpartyGroupDto groupByReference(String groupReference) {
        try {
            return counterparty.get().uri("/api/initiation/groups/by-reference/{r}", groupReference)
                    .retrieve().body(CounterpartyGroupDto.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            throw ApiException.notFound("No group: " + groupReference);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "counterparty-service unavailable for group " + groupReference + ": " + e.getMessage());
        }
    }

    public CounterpartyGroupDto groupByIdOrNull(Long groupId) {
        if (groupId == null) return null;
        try {
            return counterparty.get().uri("/api/initiation/groups/{id}", groupId)
                    .retrieve().body(CounterpartyGroupDto.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            return null;       // 404 = group no longer exists; treat as untagged
        } catch (Exception e) {
            log.warn("counterparty-service group lookup unavailable for id={} ({})", groupId, e.getMessage());
            return null;
        }
    }

    public GroupExposureDto groupExposure(String groupReference) {
        try {
            return counterparty.get()
                    .uri("/api/initiation/groups/by-reference/{r}/exposure", groupReference)
                    .retrieve().body(GroupExposureDto.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            throw ApiException.notFound("No group: " + groupReference);
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
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            return List.of();   // 404 = no apps for this counterparty; not an outage
        } catch (Exception e) {
            // Transient / upstream failure — surface to the caller so a 0-app rollup
            // can't be mistaken for a real "no applications" answer.
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "origination-service unavailable for applications-by-counterparty "
                            + counterpartyRef + ": " + e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CounterpartyDto(Long id, String reference, String legalName, String segment,
                                  String sector, String country, String industry, String subIndustry,
                                  String businessSegment, String borrowerType, String recordType,
                                  String lifecycleStatus, String rmId, Long groupId, String externalId,
                                  String createdAt) {
    }

    public CounterpartyDto counterpartyByReference(String reference) {
        try {
            return counterparty.get().uri("/api/counterparties/by-reference/{r}", reference)
                    .retrieve().body(CounterpartyDto.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
            throw ApiException.notFound("No counterparty: " + reference);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "counterparty-service unavailable for " + reference + ": " + e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditEventDto(Long id, String actor, String actorType, String eventType,
                                String subjectType, String subjectId, String summary,
                                String occurredAt) {
    }

    /** Audit history for any subject; used by CPT to detect missing call reports / RM changes. */
    public List<AuditEventDto> auditFor(RestClient client, String type, String id) {
        try {
            AuditEventDto[] arr = client.get()
                    .uri(uri -> uri.path("/api/audit/subject").queryParam("type", type).queryParam("id", id).build())
                    .retrieve().body(AuditEventDto[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            log.warn("audit lookup unavailable for {}/{} ({})", type, id, e.getMessage());
            return List.of();
        }
    }

    /** Counterparty-service audit (RM ownership history etc.). */
    public List<AuditEventDto> counterpartyAudit(String type, String id) {
        return auditFor(counterparty, type, id);
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
