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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FxView(String base, Map<String, Double> rates) {
    }

    /** A single FX quote derived from limit-service's rate table (base = INR). */
    public record FxQuote(String from, String to, double rate, double convertedAmount) { }

    /**
     * Cross-currency conversion for the disbursement path. limit-service's FxService
     * is the platform's source of truth for FX rates; it stores rates as
     * (base-currency per unit of foreign currency) on an INR base. We compose
     * arbitrary cross-rates as: {@code amount * rate(from) / rate(to)}.
     */
    public FxQuote fxQuote(String from, String to, double amount) {
        if (from == null || to == null) throw new IllegalArgumentException("from and to required");
        if (from.equalsIgnoreCase(to)) return new FxQuote(from.toUpperCase(), to.toUpperCase(), 1.0, amount);
        FxView fx;
        try {
            fx = client.get().uri("/api/limits/eod/fx").retrieve().body(FxView.class);
        } catch (Exception e) {
            log.warn("limit-service /eod/fx unavailable ({}); cannot quote {}->{}", e.getMessage(), from, to);
            throw com.helix.common.web.ApiException.conflict(
                    "FX rates unavailable from limit-service; cannot convert " + from + " to " + to);
        }
        if (fx == null || fx.rates() == null) {
            throw com.helix.common.web.ApiException.conflict("Empty FX rates from limit-service");
        }
        Double rFrom = fx.rates().get(from.toUpperCase());
        Double rTo = fx.rates().get(to.toUpperCase());
        if (rFrom == null || rTo == null || rTo == 0) {
            throw com.helix.common.web.ApiException.badRequest(
                    "Unknown FX pair " + from + "/" + to + " — neither leg in the rate table");
        }
        double rate = rFrom / rTo;
        double converted = Math.round(amount * rate * 100.0) / 100.0;
        return new FxQuote(from.toUpperCase(), to.toUpperCase(), rate, converted);
    }
}
