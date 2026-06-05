package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

public final class MerDtos {

    private MerDtos() {
    }

    /** Manually raise a monitoring exception / renewal obligation. */
    public record RaiseRequest(@NotBlank String applicationRef, String counterpartyName, String itemType,
                               String category, @NotBlank String description, String criticality,
                               @NotBlank String dueDate, boolean recurring, String renewalFrequency,
                               Integer reminderDaysBefore, Integer escalationDaysAfter, @NotBlank String owner) {
    }

    /** Submit the document / evidence (feeds the DMS). */
    public record SubmitRequest(@NotBlank String docRef, String comment) {
    }

    /** Verifier clears (or rejects) a submitted item — segregation of duties applies. */
    public record VerifyRequest(boolean approve, String comment) {
    }

    /** Waive an exception (verifier must differ from the owner). */
    public record WaiveRequest(@NotBlank String reason) {
    }
}
