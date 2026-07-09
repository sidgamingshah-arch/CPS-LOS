package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

public class CpDtos {

    public record AddCpRequest(@NotBlank String facilityRef, @NotBlank String code,
                               @NotBlank String title, String description, Boolean mandatory) {
    }

    public record ClearRequest(String evidenceRef, String note) {
    }

    public record WaiveRequest(@NotBlank String reason, String note) {
    }

    public record RejectRequest(@NotBlank String reason) {
    }

    public record CpBlocker(String code, String title, String description) { }

    public record CpGateResult(String facilityRef, boolean canDrawdown,
                               int mandatoryOpen, int mandatoryTotal,
                               java.util.List<CpBlocker> blockers) {
    }
}
