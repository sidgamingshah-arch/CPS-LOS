package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** Document-comparison payloads. DTOs are records (project convention). */
public class DocCompareDtos {

    /**
     * Compute a comparison of two artifacts. {@code kind} is PROPOSAL_VERSIONS |
     * DOCUMENT_VERSIONS; {@code leftRef}/{@code rightRef} select the two artifacts
     * (version numbers for proposals, document ids for generated documents).
     */
    public record CompareRequest(@NotBlank String kind, @NotBlank String subjectRef,
                                 @NotBlank String leftRef, @NotBlank String rightRef) {
    }

    /** One section's change: type + the old/new rendered value (null when absent on a side). */
    public record DiffRow(String section, String changeType, String oldValue, String newValue) {
    }

    /**
     * The full comparison view returned by the API — the persisted record's fields plus the
     * parsed change {@code diff} rows and the left/right rendered artifact bodies for the
     * side-by-side surface.
     */
    public record ComparisonView(String comparisonRef, String kind, String subjectRef,
                                 String leftRef, String rightRef, String leftLabel, String rightLabel,
                                 int added, int removed, int changed, int unchanged,
                                 boolean advisory, String createdBy, Instant createdAt,
                                 List<DiffRow> diff) {
    }
}
