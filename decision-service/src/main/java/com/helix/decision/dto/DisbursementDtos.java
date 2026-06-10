package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class DisbursementDtos {

    public record RequestDrawdownRequest(@NotBlank String facilityRef, @Positive double amount,
                                         String currency, String purpose, String narrative) {
    }

    public record RejectRequest(@NotBlank String reason) {
    }

    public record AuthoriseRequest(String note) {
    }
}
