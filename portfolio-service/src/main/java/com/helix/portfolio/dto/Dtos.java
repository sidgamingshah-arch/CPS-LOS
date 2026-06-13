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
                                    double limitPct, double limitAmount, double utilisation, boolean breach,
                                    String band) {
    }

    /** Per-dimension early-warning band tally: how many buckets sit in each band. */
    public record BandCounts(int normal, int watch, int warning, int breach) {
    }

    // ---- correlation-stressed concentration ----

    /**
     * One sector's response to a correlation-propagated macro shock. {@code correlationToShock}
     * is how strongly this sector co-moves with the shocked one (1.0 for the shocked sector
     * itself); the stressed PD is the base PD scaled by the shock weighted by that correlation.
     */
    public record SectorStressRow(String sector, double exposure, double share,
                                  double correlationToShock, double avgBasePd, double avgStressedPd,
                                  double baseExpectedLoss, double stressedExpectedLoss,
                                  double incrementalLoss) {
    }

    /**
     * Correlation-stressed concentration view. A shock to one sector propagates through the
     * correlation matrix into every correlated sector; the headline is the stressed expected
     * loss vs the capital buffer — the "hidden" concentration that name-level diversification
     * masks but a single macro event reveals.
     */
    public record ConcentrationStressView(String jurisdiction, String shockedSector, double pdMultiplier,
                                          double capitalBase, double totalExposure,
                                          double baseExpectedLoss, double stressedExpectedLoss,
                                          double incrementalLoss, double stressedLossPctOfCapital,
                                          boolean capitalBreach, List<SectorStressRow> sectors,
                                          List<String> alerts) {
    }

    public record StressRequest(String shockedSector, Double pdMultiplier,
                                Double capitalBufferPct, java.util.Map<String, Double> correlationOverrides) {
    }

    public record ConcentrationView(String jurisdiction, double totalExposure, double capitalBase,
                                    List<ConcentrationLine> singleName, List<ConcentrationLine> sector,
                                    List<ConcentrationLine> segment, List<String> breaches) {
    }

    /** One dimension in the multi-dimensional concentration view. */
    public record ConcentrationDimension(String dimension, String basis, double limitPct,
                                         double limitAmount, double hhi, int bucketCount,
                                         double topBucketShare, int breachCount,
                                         BandCounts bands, List<ConcentrationLine> lines) {
    }

    /** Multi-dimensional concentration: every configured dimension + intersections. */
    public record MultiDimConcentrationView(String jurisdiction, double totalExposure, double capitalBase,
                                            int dimensionCount, int totalBreaches,
                                            List<ConcentrationDimension> dimensions,
                                            List<String> breaches) {
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
