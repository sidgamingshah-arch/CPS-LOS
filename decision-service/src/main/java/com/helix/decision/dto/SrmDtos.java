package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

/** Request DTOs for the SRM (structured review / renewal) API (/api/srm). DTOs are records. */
public final class SrmDtos {

    private SrmDtos() {
    }

    /** Create an SRM review for an obligor or facility; checklist materialised from SRM_CHECKLIST. */
    public record CreateSrmRequest(String subjectType, @NotBlank String subjectRef,
                                   String counterpartyName, String title, String checklistKey) {
    }

    /** Mark (or unmark) a materialised checklist item; {@code done} defaults to true when absent. */
    public record MarkItemRequest(Boolean done, String comment) {
    }
}
