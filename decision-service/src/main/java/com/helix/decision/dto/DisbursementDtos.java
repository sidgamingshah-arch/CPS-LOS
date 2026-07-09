package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public class DisbursementDtos {

    public record RequestDrawdownRequest(@NotBlank String facilityRef, @Positive double amount,
                                         String currency, String purpose, String narrative,
                                         Integer milestoneSequence) {
    }

    public record RejectRequest(@NotBlank String reason) {
    }

    public record AuthoriseRequest(String note) {
    }

    /** Edit a DRAFT drawdown. Any null field is left unchanged. */
    public record AmendRequest(Double amount, String currency, String purpose, String narrative,
                               Integer milestoneSequence) {
    }

    public record CancelRequest(String reason) {
    }

    /** Post-release correction — reason is mandatory. */
    public record ReverseRequest(@NotBlank String reason) {
    }
}
