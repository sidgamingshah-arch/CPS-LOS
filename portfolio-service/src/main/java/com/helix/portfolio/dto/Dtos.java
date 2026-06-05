package com.helix.portfolio.dto;

import java.util.List;
import java.util.Map;

public final class Dtos {

    private Dtos() {
    }

    public record RegisterExposureRequest(int daysPastDue) {
    }

    public record DispositionRequest(String status) {
    }

    public record ConcentrationLine(String key, String label, double exposure, double share,
                                    double limitPct, double limitAmount, double utilisation, boolean breach) {
    }

    public record ConcentrationView(String jurisdiction, double totalExposure, double capitalBase,
                                    List<ConcentrationLine> singleName, List<ConcentrationLine> sector,
                                    List<ConcentrationLine> segment, List<String> breaches) {
    }

    public record PortfolioSummary(long exposureCount, double totalEad, double totalRwa,
                                   double totalReportedProvision, Map<String, Long> byStage,
                                   Map<String, Double> provisionByStage, long openSignals) {
    }

    public record StressScenario(String name, double pdMultiplier, double lgdAddOn) {
    }

    public record StressOutcome(String scenario, double baselineEcl, double stressedEcl,
                                double eclIncrease, double stressedRwa) {
    }

    public record StressResult(List<StressOutcome> outcomes) {
    }
}
