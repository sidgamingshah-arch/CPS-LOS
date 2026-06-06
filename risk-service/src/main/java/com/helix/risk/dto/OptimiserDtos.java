package com.helix.risk.dto;

import java.util.List;
import java.util.Map;

public final class OptimiserDtos {

    private OptimiserDtos() {
    }

    /**
     * A goal-seek pricing-optimisation request. {@code targetRaroc} is the floor.
     * Optional caps/floors let the analyst constrain the search: e.g. cap the rate at
     * 0.12 and let the optimiser find the fee or the LGD-cover that closes the gap.
     */
    public record OptimiseRequest(Double targetRaroc, Double rateCap, Double feeBpsCap, Double maxCollateralCover) {
    }

    public record Scenario(String name, double rate, double feeBps, double lgdAfterCollateral,
                           double raroc, boolean meetsTarget, String constraintHit, Map<String, Object> breakdown) {
    }

    public record OptimisationResult(String applicationReference, double baselineRate, double baselineRaroc,
                                     double targetRaroc, double hurdleRaroc, boolean achievable, Scenario recommended,
                                     List<Scenario> scenarios, boolean advisory) {
    }

    /** Propose a rate below the recommended rate (a concession) for approval routing. */
    public record PricingExceptionRequest(Double proposedRate, String reason) {
    }

    public record PricingExceptionDecision(boolean approve, String comment) {
    }
}
