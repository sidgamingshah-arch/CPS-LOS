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
}
