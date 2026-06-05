package com.helix.decision.dto;

import java.util.Map;

public final class DocGenDtos {

    private DocGenDtos() {
    }

    public record GenerateRequest(String templateKey, Map<String, Object> variables) {
    }

    /** Add a clause from TNC_MASTER (or a custom inline clause). */
    public record AddClauseRequest(String clauseRef, String tncRecordKey, String customTitle, String customText,
                                   Integer position) {
    }

    public record EditClauseRequest(String text) {
    }

    public record ConfirmRequest(String comment) {
    }
}
