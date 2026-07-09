package com.helix.decision.dto;

public final class CptDtos {

    private CptDtos() {
    }

    public record GenerateCptRequest(
            Double trendFactorOverride,    // optional override for wallet-sizing base trend
            String note) {
    }

    public record ReviewCptRequest(boolean approve, String note) {
    }
}
