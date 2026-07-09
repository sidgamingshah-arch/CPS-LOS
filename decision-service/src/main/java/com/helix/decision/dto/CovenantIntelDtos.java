package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

public final class CovenantIntelDtos {

    private CovenantIntelDtos() {
    }

    /** Free-text covenant clauses lifted from a credit proposal, for extraction. */
    public record ExtractCovenantsRequest(@NotBlank String text) {
    }

    /** A borrower-submitted covenant compliance certificate, for assessment. */
    public record AssessCertificateRequest(@NotBlank String text) {
    }

    /** Edits applied at confirm time (all optional — null keeps the extracted value). */
    public record ConfirmExtractionRequest(
            String covenantType, String metric, String operator, Double threshold,
            String testFrequency, String breachSeverity, String note) {
    }

    public record ReviewRequest(String note) {
    }
}
