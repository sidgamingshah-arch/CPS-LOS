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

    /** Sensitivity: base vs a driver flexed by delta — the headline is the final-year DSCR move. */
    public record SensitivityView(String driver, double delta,
                                  double baseValue, double flexedValue,
                                  List<ProjectionYear> base, List<ProjectionYear> flexed,
                                  double baseFinalDscr, double flexedFinalDscr) { }
}
