package com.helix.origination.dto;

import jakarta.validation.constraints.NotBlank;

public final class CollateralIntelDtos {

    private CollateralIntelDtos() {
    }

    /** Free-text payload from an uploaded collateral document (no OCR; assumed extracted). */
    public record ExtractCollateralRequest(
            @NotBlank String documentKind,    // VALUATION_REPORT | TITLE_DEED | INSURANCE_POLICY | VEHICLE_RC | BOND_CERT | PG_DEED
            @NotBlank String text) {
    }

    /** Edits applied at confirm time — null keeps the extracted value. */
    public record ConfirmCollateralExtractionRequest(
            String description,
            Double marketValue,
            String valuationDate,
            String valuationSource,
            Double haircut,
            String owner,
            String location,
            String perfectionStatus,
            Long facilityId,
            String note) {
    }

    public record RevalueRequest(
            double newMarketValue,
            double drawnExposure,
            String effectiveDate,
            String trigger,            // VALUATION_UPDATE | FX_REVAL | EXPOSURE_INCREASE | PERIODIC
            Double ltvThreshold,       // optional override; defaults to 0.80
            String note) {
    }

    public record ReviewRequest(boolean apply, String note) {
    }
}
