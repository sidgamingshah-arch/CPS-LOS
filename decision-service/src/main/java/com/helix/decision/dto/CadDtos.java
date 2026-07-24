package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class CadDtos {

    private CadDtos() {
    }

    public record InitiateCadRequest(@NotBlank String applicationRef, String counterpartyName, String cpType) {
    }

    public record UpdateItemRequest(@NotBlank String status, String docRef, String comment) {
    }

    public record RaiseDeviationRequest(@NotBlank String type, @NotBlank String reason) {
    }

    public record DeviationDecisionRequest(boolean approve, String comment) {
    }

    public record LimitReleaseRequest(boolean processingFeeAmortised, boolean lienMarked,
                                      boolean cashMarginCaptured, String comment) {
    }

    public record CadCaseView(Object cadCase, List<?> items, List<?> deviations) {
    }

    /**
     * Advisory CAD document-AI verification request over a checklist item.
     * {@code verificationType} is SIGNATURE or PROPERTY_DOC (default PROPERTY_DOC).
     * For SIGNATURE, {@code claimedSignatory} is the name on the document and
     * {@code specimenSignatory} the on-file specimen. For PROPERTY_DOC, {@code docText}
     * is the document text to extract from and {@code mandatoryFields} optionally
     * overrides the default mandatory-field set.
     */
    public record VerifyDocRequest(String verificationType, String docText,
                                   String claimedSignatory, String specimenSignatory,
                                   List<String> mandatoryFields) {
    }

    /** Human accept/reject of an advisory doc-verification finding (note optional). */
    public record DocVerificationDecisionRequest(String note) {
    }
}
