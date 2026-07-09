package com.helix.portfolio.reports;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The user-authored report shape. Submitted inline to {@code /run} for live
 * preview, or saved as a {@code REPORT_DEFINITION} master and executed by key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportDefinition(
        String title,
        String dataset,
        List<String> dimensions,
        List<Measure> measures,
        List<Filter> filters,
        List<SortClause> sort,
        Integer limit
) {
    public record Measure(String field, String agg, String as) {}
    public record Filter(String field, String op, Object value) {}
    public record SortClause(String by, String dir) {}
}
