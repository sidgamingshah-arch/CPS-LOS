package com.helix.risk.dto;

public final class AdvisoryDtos {

    private AdvisoryDtos() {
    }

    /**
     * A macro scenario. Positive {@code interestRateBps} = rates up; negative
     * {@code gdpGrowthDeltaPct} = slowdown; positive {@code fxDepreciationPct} =
     * local-currency depreciation; {@code sectorOutlook} ∈ IMPROVING|STABLE|DETERIORATING.
     */
    public record MacroScenarioRequest(String scenarioName, Double interestRateBps, Double gdpGrowthDeltaPct,
                                       Double fxDepreciationPct, String sectorOutlook, Double commodityShockPct) {
    }
}
