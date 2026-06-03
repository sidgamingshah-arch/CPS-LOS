package com.helix.origination.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

public final class Dtos {

    private Dtos() {
    }

    public record CreateApplicationRequest(
            @NotNull Long counterpartyId,
            @NotBlank String counterpartyRef,
            @NotBlank String counterpartyName,
            @NotBlank String jurisdiction,
            @NotBlank String segment,
            @NotBlank String facilityType,
            @Positive double requestedAmount,
            @NotBlank String currency,
            @Positive int tenorMonths,
            String purpose,
            String collateralType,
            double collateralValue,
            boolean secured) {
    }

    public record UploadDocumentRequest(@NotBlank String fileName, String declaredType) {
    }

    public record StatusUpdateRequest(@NotBlank String status) {
    }

    /** Raw financials for spreading. Each line carries its source provenance. */
    public record SpreadRequest(@NotNull List<PeriodInput> periods) {

        public record PeriodInput(
                @NotBlank String label,
                @NotBlank String gaap,
                @NotBlank String currency,
                @NotNull Map<String, LineInput> lines) {
        }

        public record LineInput(double value, String sourceDocument, String sourcePage,
                                String coordinates, Double confidence) {
        }
    }

    public record OverrideRequest(@NotNull Double value, String reason) {
    }

    public record AddFacilityRequest(
            @NotBlank String facilityType,
            @Positive double amount,
            @NotBlank String currency,
            @Positive int tenorMonths,
            String purpose,
            Double indicativeRate) {
    }

    public record AddCollateralRequest(
            @NotBlank String collateralType,
            @NotBlank String description,
            @Positive double marketValue,
            String valuationDate,
            String valuationSource,
            double haircut,
            String owner,
            String location,
            @NotBlank String perfectionStatus,
            Long facilityId) {
    }

    // ---- responses ----

    public record CellView(Long id, String taxonomyKey, String label, boolean derived, double value,
                           double extractedValue, double confidence, String sourceDocument, String sourcePage,
                           String sourceCoordinates, boolean overridden, Double overrideValue,
                           String overrideReason, boolean materialOverride, String overriddenBy) {
    }

    public record PeriodAnalysis(Long periodId, String label, String gaap, String currency,
                                 List<CellView> lines, Map<String, Double> ratios) {
    }

    public record SpreadAnalysis(String applicationReference, boolean spreadConfirmed,
                                 List<PeriodAnalysis> periods, Map<String, Double> trends,
                                 List<String> benchmarkFlags) {
    }

    /** Snapshot consumed by risk-service to rate, capitalise and price the deal. */
    public record CreditInputs(String applicationReference, Long counterpartyId, String counterpartyRef,
                               String counterpartyName, String jurisdiction, String segment, String facilityType,
                               double requestedAmount, String currency, int tenorMonths, String collateralType,
                               double collateralValue, boolean secured, boolean spreadConfirmed,
                               Map<String, Double> latestFinancials, Map<String, Double> ratios,
                               Map<String, Double> trends) {
    }

    /** Full deal envelope used by credit proposal generation (decision-service). */
    public record DealEnvelope(
            String applicationReference, String counterpartyName, String jurisdiction, String segment,
            double totalProposedAmount, String currency, int tenorMonths,
            List<FacilityView> facilities, List<CollateralView> collaterals, double totalCollateralCover,
            Map<String, Double> latestFinancials, Map<String, Double> ratios) {
    }

    public record FacilityView(Long id, String reference, int ordinal, boolean primary,
                               String facilityType, double amount, String currency,
                               int tenorMonths, String purpose, Double indicativeRate) {
    }

    public record CollateralView(Long id, Long facilityId, String collateralType, String description,
                                 double marketValue, double haircut, double effectiveValue,
                                 String perfectionStatus, String valuationDate, String owner) {
    }
}
