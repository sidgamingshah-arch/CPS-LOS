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
            String dissent,
            // Optional structured conditions of sanction. When present on an APPROVE /
            // CONDITIONAL_APPROVE, each is materialised into the pre-disbursement CP register
            // (source=SANCTION) so the existing gate enforces it. Absent field == current behaviour.
            List<ConditionSpec> conditionsPrecedent) {
    }

    /**
     * One structured condition of sanction. {@code facilityRef} pins the CP to a single
     * facility; when blank the condition fans out to every facility on the deal.
     */
    public record ConditionSpec(String facilityRef, String code, String title,
                                String description, Boolean mandatory) {
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
