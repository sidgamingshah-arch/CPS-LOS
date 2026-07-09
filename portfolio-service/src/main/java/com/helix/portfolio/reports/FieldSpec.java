package com.helix.portfolio.reports;

import java.util.List;

/**
 * One field in a dataset's whitelist. Type drives which operators and
 * aggregations are valid (a `STRING` cannot be summed; an `INT` can be summed,
 * averaged, or compared with GT/LT; an `ENUM` is selected via IN/EQ).
 */
public record FieldSpec(
        String name,
        Type type,
        boolean dimension,    // can appear in groupBy
        boolean measure,      // can appear as a measure (SUM/AVG/etc.)
        List<String> enumValues   // for ENUM type only — display-only hint, not a hard restrict
) {
    public enum Type { STRING, NUMBER, INT, ENUM }

    public static FieldSpec dim(String name, Type type) {
        return new FieldSpec(name, type, true, false, List.of());
    }

    public static FieldSpec enumDim(String name, List<String> values) {
        return new FieldSpec(name, Type.ENUM, true, false, values);
    }

    public static FieldSpec metric(String name, Type type) {
        return new FieldSpec(name, type, false, true, List.of());
    }

    public static FieldSpec dimAndMetric(String name, Type type) {
        return new FieldSpec(name, type, true, true, List.of());
    }
}
