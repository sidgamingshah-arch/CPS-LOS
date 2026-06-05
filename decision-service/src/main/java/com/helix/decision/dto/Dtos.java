package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record DecisionRequest(
            @NotBlank String outcome,        // APPROVE | CONDITIONAL_APPROVE | DECLINE | REFER
            @NotBlank String role,           // decider's authority role
            String rationale,
            List<String> conditions,
            String dissent) {
    }

    public record AddCovenantRequest(
            @NotBlank String covenantType,
            @NotBlank String metric,
            @NotBlank String operator,
            double threshold,
            @NotBlank String testFrequency,
            String source,
            int curePeriodDays,
            @NotBlank String breachSeverity,
            @NotNull List<String> onBreach) {
    }
}
