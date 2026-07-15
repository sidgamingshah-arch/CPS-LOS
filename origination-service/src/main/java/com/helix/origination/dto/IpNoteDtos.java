package com.helix.origination.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/** Request DTOs for the In-Principle note engine (/api/ip-notes). DTOs are records. */
public final class IpNoteDtos {

    private IpNoteDtos() {
    }

    /** Raise a DRAFT IP note. The proposed-structure primitives feed the LoanApplication
     *  created on convert; {@code payload} carries any additional structure detail. */
    public record CreateIpNoteRequest(
            @NotNull Long counterpartyId,
            @NotBlank String counterpartyRef,
            @NotBlank String counterpartyName,
            @NotBlank String jurisdiction,
            @NotBlank String segment,
            @NotBlank String facilityType,
            @Positive double proposedAmount,
            @NotBlank String currency,
            @Positive int tenorMonths,
            String purpose,
            String prospectSummary,
            Map<String, Object> payload) {
    }

    /** Optional decision note carried on approve. */
    public record DecisionNoteRequest(String note) {
    }

    /** Mandatory reason on reject (blank rejected 400 by the service). */
    public record ReasonRequest(String reason) {
    }
}
