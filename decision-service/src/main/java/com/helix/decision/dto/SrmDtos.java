package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

/** Request DTOs for the SRM (structured review / renewal) API (/api/srm). DTOs are records. */
public final class SrmDtos {

    private SrmDtos() {
    }

    /**
     * Create an SRM review for an obligor or facility; checklist materialised from SRM_CHECKLIST.
     * {@code applicationRef} is the OPTIONAL explicit MER key (the application reference whose
     * next-review the AUTHORIZED renewal advances). It may differ from {@code subjectRef} — for a
     * {@code subjectType=Counterparty} review the subjectRef is a counterparty ref, a different ID
     * namespace from MER's applicationReference. When omitted for a Counterparty subject, the
     * obligor's application(s) are resolved from origination at refresh time.
     */
    public record CreateSrmRequest(String subjectType, @NotBlank String subjectRef, String applicationRef,
                                   String counterpartyName, String title, String checklistKey) {
    }

    /** Mark (or unmark) a materialised checklist item; {@code done} defaults to true when absent. */
    public record MarkItemRequest(Boolean done, String comment) {
    }
}
