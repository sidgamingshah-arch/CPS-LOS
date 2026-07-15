package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** DTOs for the Mortgage / MOE security-perfection module (@code /api/perfection}). */
public final class PerfectionDtos {

    private PerfectionDtos() {
    }

    /** subjectType ∈ OBLIGOR|FACILITY|COLLATERAL; applicationRef optional (deal linkage for the gate). */
    public record CreateCaseRequest(@NotBlank String subjectType, @NotBlank String subjectRef,
                                    String applicationRef, String checklistKey) {
    }

    /** Complete / waive a step. {@code role} is the acting role context (must match the step's ownerRole). */
    public record StepActionRequest(@NotBlank String role, String evidence, String notes) {
    }

    /** Raise the EXTERNAL_VENDOR RFQ for a VENDOR-owned step. */
    public record VendorRfqRequest(String vendorId, String question) {
    }

    public record CaseView(Object perfectionCase, List<?> steps) {
    }
}
