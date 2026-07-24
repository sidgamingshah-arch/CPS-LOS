package com.helix.origination.dto;

import jakarta.validation.constraints.NotBlank;

public final class CollateralIntelDtos {

    private CollateralIntelDtos() {
    }

    /**
     * Collateral-document extraction request. The document text comes from EITHER:
     * <ul>
     *   <li>{@code text} — typed / pasted free text (no OCR; assumed already extracted), or</li>
     *   <li>{@code documentId} — a Document already uploaded via {@code /documents/upload}, whose
     *       real extracted text (PDFBox / UTF-8 / OCR) is fed to the SAME extraction pipeline. The
     *       upload creates a first-class DMS-backed Document that also appears in the deal's
     *       document list. When supplied, {@code documentId} takes precedence over {@code text}.</li>
     * </ul>
     * At least one of the two must be present; validated in the service.
     */
    public record ExtractCollateralRequest(
            @NotBlank String documentKind,    // VALUATION_REPORT | TITLE_DEED | INSURANCE_POLICY | VEHICLE_RC | BOND_CERT | PG_DEED
            String text,
            Long documentId) {
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
