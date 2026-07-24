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
            /** Nullable so the create path can tell "not supplied" (null → blank, so a
             *  FIELD_POLICY requiredWhen can fire) from an explicit 0 (present). */
            Double collateralValue,
            boolean secured) {
    }

    public record UploadDocumentRequest(@NotBlank String fileName, String declaredType) {
    }

    public record StatusUpdateRequest(@NotBlank String status) {
    }

    /** Raw financials for spreading. Each line carries its source provenance. */
    public record SpreadRequest(
            @NotNull List<PeriodInput> periods,
            /** Optional Level-1 presentation currency to normalise every period into.
             *  Defaults to the latest period's native currency when omitted. */
            String presentationCurrency,
            /** Optional origin marker for the version timeline: MANUAL | DOC_INTEL |
             *  RESUBMISSION. Derived when omitted (first spread MANUAL, later RESUBMISSION). */
            String source,
            /** Optional analyst note recorded on the version-timeline entry. */
            String note) {

        public record PeriodInput(
                @NotBlank String label,
                @NotBlank String gaap,
                @NotBlank String currency,
                /** Optional analyst-supplied period-end rate (native -> presentation).
                 *  When omitted and the period currency differs from the presentation
                 *  currency, the rate is resolved from the dated FX_RATE master as at
                 *  {@link #periodEnd}, then the current spot table. */
                Double fxToPresentation,
                /** Optional ISO period-end date (e.g. 2024-03-31). When present, the
                 *  native->presentation rate is taken AS AT this date from the dated
                 *  FX_RATE master rather than today's spot. */
                String periodEnd,
                @NotNull Map<String, LineInput> lines) {
        }

        public record LineInput(double value, String sourceDocument, String sourcePage,
                                String coordinates, Double confidence) {
        }
    }

    public record OverrideRequest(@NotNull Double value, String reason) {
    }

    /**
     * Request to pre-fill a DRAFT spread from a CONFIRMED {@link com.helix.origination.entity.DocExtraction}.
     * All fields optional: {@code extractionId} null → the latest CONFIRMED extraction for the deal;
     * label / gaap / currency default from the extraction's reporting period, IND_AS and the deal currency.
     * The produced spread is advisory (DRAFT / unconfirmed) and still passes the analyst confirm-gate.
     */
    public record SpreadFromExtractionRequest(Long extractionId, String periodLabel, String gaap,
                                              String currency, String note) {
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

    public record AddSublimitRequest(
            @NotBlank String code,
            @NotBlank String productType,
            @Positive double amount,
            @NotBlank String currency,
            Integer tenorMonths,
            String purpose,
            String interchangeableGroup) {
    }

    // ---- responses ----

    public record CellView(Long id, String taxonomyKey, String label, boolean derived, double value,
                           double extractedValue, double confidence, String sourceDocument, String sourcePage,
                           String sourceCoordinates, boolean overridden, Double overrideValue,
                           String overrideReason, boolean materialOverride, String overriddenBy) {
    }

    public record PeriodAnalysis(Long periodId, String label, String gaap, String currency,
                                 List<CellView> lines, Map<String, Double> ratios,
                                 /** Level-1: currency the period is normalised into, the FX rate used,
                                  *  and the monetary lines restated into that currency. Ratios are
                                  *  unit-free so they are identical in either currency. */
                                 String presentationCurrency, Double fxToPresentation,
                                 Map<String, Double> presentationValues,
                                 /** Period-end date and where the FX rate came from
                                  *  (SUPPLIED | DATED_MASTER | CURRENT_SPOT | SAME_CURRENCY). */
                                 String periodEnd, String fxRateSource) {
    }

    public record SpreadAnalysis(String applicationReference, boolean spreadConfirmed,
                                 List<PeriodAnalysis> periods, Map<String, Double> trends,
                                 List<String> benchmarkFlags,
                                 /** The presentation currency every period is normalised into and
                                  *  whether all periods share a single native currency. Trends are
                                  *  computed on the normalised (presentation-currency) values. */
                                 String presentationCurrency, boolean currencyConsistent,
                                 /** The resolved FINANCIAL_TEMPLATE (chart-of-accounts augmentation) key. */
                                 String financialTemplate) {
    }

    /** One row of the append-only spread version timeline (metadata only, no snapshot payload). */
    public record SpreadVersionView(int versionNo, String createdBy, java.time.Instant createdAt,
                                    String source, boolean confirmed, String confirmedBy,
                                    java.time.Instant confirmedAt, String note) {
    }

    /** A single archived spread version including the full analysis snapshot as recorded. */
    public record SpreadVersionDetail(int versionNo, String createdBy, java.time.Instant createdAt,
                                      String source, boolean confirmed, String confirmedBy,
                                      java.time.Instant confirmedAt, String note,
                                      com.fasterxml.jackson.databind.JsonNode snapshot) {
    }

    /** Snapshot consumed by risk-service to rate, capitalise and price the deal. */
    public record CreditInputs(String applicationReference, Long counterpartyId, String counterpartyRef,
                               String counterpartyName, String jurisdiction, String segment, String sector,
                               String facilityType,
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
            Map<String, Double> latestFinancials, Map<String, Double> ratios,
            /** Multi-period spread financials (latest first), so a CAM can render a MULTI-YEAR trend
             *  table without recomputing anything — each period's native line values, quoted verbatim.
             *  Additive field; consumers that ignore it see byte-identical behaviour. */
            List<PeriodFinancials> periodFinancials) {
    }

    /** One period's native line values for the multi-year trend table (verbatim spread cells). */
    public record PeriodFinancials(String label, String currency, Map<String, Double> values) {
    }

    public record FacilityView(Long id, String reference, int ordinal, boolean primary,
                               String facilityType, double amount, String currency,
                               int tenorMonths, String purpose, Double indicativeRate,
                               String rateType, String benchmarkCode, Double spreadBps,
                               Integer resetFrequencyMonths,
                               List<SublimitView> sublimits, List<InterchangeabilityGroupView> interchangeabilityGroups,
                               double sublimitTotal, double sublimitHeadroom) {
    }

    public record SublimitView(Long id, Long facilityId, int ordinal, String code, String productType,
                               double amount, String currency, Integer tenorMonths, String purpose,
                               String interchangeableGroup, boolean fungible) {
    }

    /** Pool view: members of an interchangeable group share a combined cap. */
    public record InterchangeabilityGroupView(String groupKey, double combinedCap, String currency,
                                              List<String> memberCodes, int memberCount) {
    }

    public record CollateralView(Long id, Long facilityId, String collateralType, String description,
                                 double marketValue, double haircut, double effectiveValue,
                                 String perfectionStatus, String valuationDate, String owner) {
    }
}
