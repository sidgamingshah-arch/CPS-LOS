package com.helix.risk.dto;

import java.util.List;
import java.util.Map;

public class ProjectionDtos {

    /** One projected year: year index (1..N) + each line's computed value. */
    public record ProjectionYear(int year, Map<String, Double> values) { }

    /** A driver assumption (key, label, effective value, template default). */
    public record DriverView(String key, String label, double value, double defaultValue) { }

    /** Full projection view: template, drivers, base-year actuals, and the projected grid. */
    public record ProjectionView(String applicationReference, String templateKey, int templateVersion,
                                 int horizonYears, String status, boolean advisory,
                                 List<DriverView> drivers,
                                 List<String> lineKeys, List<String> lineLabels,
                                 Map<String, Double> baseYear,
                                 List<ProjectionYear> years,
                                 String authoritativeGrade, boolean gradeUnchanged,
                                 String confirmedBy) { }

    public record DriverOverrideRequest(Map<String, Double> drivers) { }

    public record SensitivityRequest(String driver, Double delta) { }

    // ---- Monte-Carlo simulation (advisory ML overlay; never moves authoritative figures) ----

    /** One driver's simulated distribution: mean (effective value) + σ-fraction + calibration sources. */
    public record MonteCarloDriver(String key, String label, double mean, double volatility,
                                   List<String> sources) { }

    /** Final-year distribution stats for one projected line. */
    public record LineStat(String line, String label, double p10, double p50, double p90, double mean) { }

    /**
     * Monte-Carlo projection result: N stochastic runs of the deterministic per-line proforma with
     * drivers sampled from their calibrated distributions. Headline is the final-year DSCR band +
     * breach probability. Advisory — the authoritative grade/capital/pricing are untouched.
     */
    public record MonteCarloView(String applicationReference, String templateKey, int templateVersion,
                                 int horizonYears, int iterations, long seed, boolean advisory,
                                 List<MonteCarloDriver> drivers,
                                 double dscrP10, double dscrP50, double dscrP90, double dscrMean,
                                 double dscrBreachProbability,
                                 List<LineStat> finalYearLines,
                                 String authoritativeGrade, boolean gradeUnchanged,
                                 List<String> methodologyNotes) { }

    /** Sensitivity: base vs a driver flexed by delta — the headline is the final-year DSCR move. */
    public record SensitivityView(String driver, double delta,
                                  double baseValue, double flexedValue,
                                  List<ProjectionYear> base, List<ProjectionYear> flexed,
                                  double baseFinalDscr, double flexedFinalDscr) { }
}
