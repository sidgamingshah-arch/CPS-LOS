package com.helix.portfolio.reports;

import java.util.List;
import java.util.Map;

/** Tabular result. {@code columns} carries display labels + types so the UI knows how to render each cell. */
public record ReportResult(
        String datasetKey,
        List<Column> columns,
        List<List<Object>> rows,
        Map<String, Object> totals,
        int scannedRows,
        int returnedRows
) {
    public record Column(String key, String label, String type, String role) {
        // role ∈ DIMENSION | MEASURE
    }
}
