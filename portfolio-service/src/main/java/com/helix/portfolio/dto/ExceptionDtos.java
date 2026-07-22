package com.helix.portfolio.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** DTOs for the unified exception / tickler register (U7). */
public final class ExceptionDtos {

    private ExceptionDtos() {
    }

    /**
     * The common, normalised shape every aggregated open item collapses to. This is a
     * read-only projection assembled best-effort from the owning services — surfacing an
     * item here never mutates its source of record.
     */
    public record ExceptionItem(String source, String type, String subjectRef, String description,
                                String owner, String dueAt, String severity, String status) {
    }

    /**
     * The rollup response. {@code warnings} lists any source that could not be reached
     * (the rollup degrades to a partial view rather than failing); {@code bySource} counts
     * the surfaced items per source for the cockpit.
     */
    public record RollupResult(String subjectRef, int totalOpen, List<ExceptionItem> items,
                               Map<String, Integer> bySource, List<String> warnings) {
    }

    public record CreateTicklerRequest(@NotBlank String subjectRef, @NotBlank String title,
                                       String description, String owner, String dueAt, String priority) {
    }

    public record AssignRequest(@NotBlank String toActor) {
    }

    public record ResolveRequest(String note) {
    }
}
