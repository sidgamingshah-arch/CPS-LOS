package com.helix.risk.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request DTOs for the Independent Risk Note API (/api/risk-notes). DTOs are records
 * (project convention). The acting identity always comes from the {@code X-Actor}
 * header — never from a body field.
 */
public final class RiskNoteDtos {

    private RiskNoteDtos() {
    }

    /** Raise a new note against a subject (application ref). Sections/action optional at draft time. */
    public record CreateRiskNoteRequest(@NotBlank String subjectRef,
                                        Map<String, Object> sections,
                                        String recommendedAction) {
    }

    /**
     * Author / update the narrative sections while the note is a DRAFT. When
     * {@code aiDraft} is true and a governed external model is configured, blank
     * sections are drafted by the LLM (advisory, {@code audit.ai}); otherwise the
     * supplied sections stand verbatim.
     */
    public record UpdateSectionsRequest(Map<String, Object> sections,
                                        Boolean aiDraft,
                                        String recommendedAction) {
    }

    /** Reassign the current work-item owner. */
    public record ReassignRequest(@NotBlank String toActor) {
    }

    /** Optional note carried on approve. */
    public record DecisionNoteRequest(String note) {
    }

    /** Mandatory reason on reject / reverse (blank rejected 400 by the service). */
    public record ReasonRequest(String reason) {
    }
}
