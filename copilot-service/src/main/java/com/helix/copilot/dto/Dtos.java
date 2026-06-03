package com.helix.copilot.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record AskRequest(@NotBlank String question, String reference) {
    }

    /** A grounded source reference for a fact in the answer (PRD §6.3/§6.6). */
    public record Citation(String source, String endpoint, String field) {
    }

    public record CopilotAnswer(
            String persona,
            String role,
            String intent,
            String answer,
            boolean grounded,
            boolean refused,
            String refusalReason,
            String suggestedAction,
            List<Citation> citations,
            List<String> scope) {
    }
}
