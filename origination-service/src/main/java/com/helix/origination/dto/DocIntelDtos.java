package com.helix.origination.dto;

import java.util.List;

public final class DocIntelDtos {

    private DocIntelDtos() {
    }

    public record ConfirmRequest(String note) {
    }

    public record RejectRequest(String reason) {
    }

    /** Normalise casual/dictated text into formal LEGAL or PLAIN business language. */
    public record NormaliseRequest(String text, String target) {     // target: LEGAL | PLAIN
    }

    public record NormaliseResponse(String target, String original, String rewritten, List<String> notes,
                                    boolean advisory) {
    }

    public record TranslateRequest(String text, String targetLanguage) {
    }

    public record TranslateResponse(String sourceLanguage, String targetLanguage, String original,
                                    String translated, double confidence, boolean advisory) {
    }

    public record DocCheckFinding(String level, String code, String message) {
    }

    public record DocCheckResponse(Long documentId, String classifiedType, boolean passed,
                                   List<DocCheckFinding> findings, boolean advisory) {
    }
}
