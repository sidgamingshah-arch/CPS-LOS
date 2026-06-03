package com.helix.risk.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Snapshot fetched from origination-service to rate, capitalise and price a deal. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditInputsDto(
        String applicationReference,
        Long counterpartyId,
        String counterpartyRef,
        String counterpartyName,
        String jurisdiction,
        String segment,
        String facilityType,
        double requestedAmount,
        String currency,
        int tenorMonths,
        String collateralType,
        double collateralValue,
        boolean secured,
        boolean spreadConfirmed,
        Map<String, Double> latestFinancials,
        Map<String, Double> ratios,
        Map<String, Double> trends) {

    public double ratio(String key) {
        return ratios == null ? 0.0 : ratios.getOrDefault(key, 0.0);
    }

    public double financial(String key) {
        return latestFinancials == null ? 0.0 : latestFinancials.getOrDefault(key, 0.0);
    }
}
