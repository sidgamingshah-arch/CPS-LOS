package com.helix.decision.dto;

public final class CommentaryDtos {

    private CommentaryDtos() {
    }

    public record DraftRequest(String section, String hint) {
    }

    public record ReviewRequest(boolean approve, String note) {
    }

    public record EditRequest(String narrative) {
    }
}
