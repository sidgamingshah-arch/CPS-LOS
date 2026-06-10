package com.helix.decision.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cross-service client for limit-service. Used by the disbursement release path
 * to book a {@code UTILISE} action on the facility's limit node — so the
 * disbursement workflow integrates with the existing limit ledger rather than
 * being a parallel system.
 */
@Component
public class LimitClient {

    private static final Logger log = LoggerFactory.getLogger(LimitClient.class);

    private final RestClient client;

    public LimitClient(@Value("${helix.limit-service.base-url}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UtilisationActionDto(String lineId, String action, double amount, String currency,
                                       String transactionRef) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UtilisationRequestDto(String cif, List<UtilisationActionDto> actions,
                                        String productProcessor, boolean overrideFlag) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActionResultDto(String lineId, String action, boolean success, String message,
                                  double newOutstanding, double newAvailable) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UtilisationResponseDto(String cif, boolean success, List<ActionResultDto> results) {
    }

    /** Books a UTILISE against the limit node identified by {@code lineId} (the node's code). */
    public UtilisationResponseDto utilise(String cif, String lineId, double amount, String currency,
                                          String transactionRef, String actor) {
        UtilisationRequestDto body = new UtilisationRequestDto(cif,
                List.of(new UtilisationActionDto(lineId, "UTILISE", amount, currency, transactionRef)),
                "disbursement-service", false);
        try {
            return client.post().uri("/api/limits/utilise")
                    .header("X-Actor", actor == null ? "disbursement" : actor)
                    .body(body)
                    .retrieve()
                    .body(UtilisationResponseDto.class);
        } catch (Exception e) {
            log.warn("limit-service unavailable for utilise {}/{} ({})", cif, lineId, e.getMessage());
            return new UtilisationResponseDto(cif, false, List.of(
                    new ActionResultDto(lineId, "UTILISE", false,
                            "limit-service unavailable: " + e.getMessage(), 0, 0)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LimitNodeDto(Long id, String reference, String cif, String applicationRef,
                               String facilityRef, String code, String currency,
                               double sanctionedAmount) {
    }

    /**
     * Resolves the limit-node entity for the given (applicationRef, facilityRef). Used
     * by the disbursement release path to find the correct {@code lineId} (the node's
     * {@code reference}) to pass to UTILISE — multiple facilities of the same type
     * would otherwise be ambiguous on {@code code} alone.
     */
    public LimitNodeDto nodeForFacility(String applicationRef, String facilityRef) {
        try {
            return client.get()
                    .uri(u -> u.path("/api/limits/by-facility")
                            .queryParam("applicationRef", applicationRef)
                            .queryParam("facilityRef", facilityRef).build())
                    .retrieve()
                    .body(LimitNodeDto.class);
        } catch (Exception e) {
            log.warn("limit lookup by-facility failed for {}/{} ({})", applicationRef, facilityRef, e.getMessage());
            return null;
        }
    }
}
