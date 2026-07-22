package com.helix.portfolio.dto;

/**
 * Request records for the global / combined cash-flow consolidation. The consolidated
 * response is the {@code GlobalCashflowAssessment} entity itself (combined figures +
 * per-member contribution list) — every figure a deterministic sum of confirmed member
 * spreads; nothing here mutates an authoritative value.
 */
public final class GlobalCashflowDtos {

    private GlobalCashflowDtos() {
    }

    /** Kick off a consolidation for one borrower group (by its group reference). */
    public record AssembleRequest(String groupReference) {
    }
}
