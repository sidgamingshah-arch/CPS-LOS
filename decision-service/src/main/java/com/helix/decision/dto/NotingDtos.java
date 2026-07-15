package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Request DTOs for the Noting engine (/api/notings). DTOs are records. */
public final class NotingDtos {

    private NotingDtos() {
    }

    public record CreateNotingRequest(@NotBlank String notingType, String subjectType,
                                      @NotBlank String subjectRef, @NotBlank String title,
                                      String narrative, Map<String, Object> payload) {
    }

    /** Optional decision note carried on approve / cad-authorize. */
    public record DecisionNoteRequest(String note) {
    }

    /** Mandatory reason on reject / reverse (blank rejected 400 by the service). */
    public record ReasonRequest(String reason) {
    }
}
