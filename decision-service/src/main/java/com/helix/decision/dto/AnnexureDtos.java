package com.helix.decision.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** CAM annexure payloads. DTOs are records (project convention). */
public class AnnexureDtos {

    /**
     * Create an annexure from an {@code ANNEXURE_TYPE} master key. The section skeleton is
     * materialised from that master (version-pinned); the author is the X-Actor, never the body.
     */
    public record CreateRequest(@NotBlank String annexureType, String subjectType, String subjectRef,
                                String title) {
    }

    /**
     * Author edits to the section content. {@code sections} carries explicit author-written
     * content (key → {title, content}); when {@code aiDraft} is true the governed LLM boundary
     * drafts prose for any still-empty section (advisory, grounded, quoting figures verbatim),
     * with {@code hint} an optional steer. Both are additive — explicit author edits always win.
     */
    public record SectionsRequest(Map<String, Object> sections, Boolean aiDraft, String hint) {
    }

    public record NoteRequest(String notes) {
    }

    public record RejectRequest(String reason) {
    }
}
