package com.helix.portfolio.reports;

import com.helix.common.audit.AuditService;
import com.helix.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic aggregation executor. Reads the dataset projection, applies
 * type-checked filters, groups by user-picked dimensions, reduces measures, and
 * returns a tabular result. No SQL, no field reflection — every access goes
 * through the registry whitelist (the security boundary).
 */
@Service
public class ReportEngine {

    private final DatasetRegistry registry;
    private final AuditService audit;
    private final int maxRows;

    public ReportEngine(DatasetRegistry registry, AuditService audit,
                        @Value("${helix.reports.max-rows:5000}") int maxRows) {
        this.registry = registry;
        this.audit = audit;
        this.maxRows = maxRows;
    }

    public ReportResult run(ReportDefinition def, String actor) {
        if (def == null) throw ApiException.badRequest("Report definition is required");
        DatasetSpec ds = registry.require(def.dataset());
        validate(def, ds);

        List<Map<String, Object>> rows = ds.rows().get();
        if (rows.size() > maxRows) {
            throw ApiException.badRequest("Dataset has " + rows.size() + " rows but the row cap is "
                    + maxRows + " — narrow the dataset or raise helix.reports.max-rows");
        }
        // 1. Filter.
        List<Map<String, Object>> filtered = filter(rows, def.filters() == null ? List.of() : def.filters(), ds);

        // 2. Group + reduce.
        List<String> dims = def.dimensions() == null ? List.of() : def.dimensions();
        List<ReportDefinition.Measure> measures =
                def.measures() == null || def.measures().isEmpty()
                        ? List.of(new ReportDefinition.Measure("*", "COUNT", "count"))
                        : def.measures();

        Map<List<Object>, Accumulator[]> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : filtered) {
            List<Object> key = new ArrayList<>(dims.size());
            for (String d : dims) key.add(row.get(d));
            Accumulator[] acc = grouped.computeIfAbsent(key, k -> newAccumulators(measures));
            for (int i = 0; i < measures.size(); i++) acc[i].accept(row);
        }

        // 3. Build rows.
        List<List<Object>> out = new ArrayList<>(grouped.size());
        for (Map.Entry<List<Object>, Accumulator[]> e : grouped.entrySet()) {
            List<Object> r = new ArrayList<>(dims.size() + measures.size());
            r.addAll(e.getKey());
            for (Accumulator a : e.getValue()) r.add(a.value());
            out.add(r);
        }

        // 4. Sort.
        List<ReportDefinition.SortClause> sort = def.sort() == null ? List.of() : def.sort();
        if (!sort.isEmpty()) {
            List<String> resultCols = resultColumnKeys(dims, measures);
            out.sort(comparatorFor(sort, resultCols));
        }

        // 5. Limit.
        int limit = def.limit() == null || def.limit() <= 0 ? out.size() : Math.min(out.size(), def.limit());
        List<List<Object>> capped = out.subList(0, limit);

        // 6. Columns metadata.
        List<ReportResult.Column> columns = new ArrayList<>();
        for (String d : dims) {
            FieldSpec f = ds.fields().get(d);
            columns.add(new ReportResult.Column(d, d, f == null ? "STRING" : f.type().name(), "DIMENSION"));
        }
        for (ReportDefinition.Measure m : measures) {
            String type = "NUMBER";
            if (!"*".equals(m.field())) {
                FieldSpec f = ds.fields().get(m.field());
                if (f != null) type = f.type().name();
            }
            columns.add(new ReportResult.Column(m.as(), m.as(), type, "MEASURE"));
        }

        // 7. Grand totals (over the un-limited grouped set — true totals, not page totals).
        Map<String, Object> totals = grandTotals(filtered, measures);

        ReportResult result = new ReportResult(ds.key(), columns, capped, totals,
                filtered.size(), capped.size());

        audit.engine("REPORT_EXECUTED", "Report", def.dataset(),
                "Ran " + (def.title() == null ? "(ad-hoc)" : def.title()) + " over " + ds.key()
                        + " — " + filtered.size() + " scanned, " + capped.size() + " grouped rows",
                Map.of("dataset", ds.key(),
                        "title", def.title() == null ? "" : def.title(),
                        "dimensions", dims,
                        "measures", measures,
                        "filters", def.filters() == null ? List.of() : def.filters(),
                        "actor", actor == null ? "" : actor,
                        "scannedRows", filtered.size(),
                        "returnedRows", capped.size()));
        return result;
    }

    // =============================================================== validation

    private void validate(ReportDefinition def, DatasetSpec ds) {
        if (def.dimensions() != null) {
            for (String d : def.dimensions()) {
                FieldSpec f = ds.fields().get(d);
                if (f == null) {
                    throw ApiException.badRequest("Dimension '" + d + "' is not a field of dataset "
                            + ds.key());
                }
                if (!f.dimension()) {
                    throw ApiException.badRequest("Field '" + d + "' is not allowed as a dimension");
                }
            }
        }
        if (def.measures() != null) {
            for (ReportDefinition.Measure m : def.measures()) {
                String agg = m.agg() == null ? "" : m.agg().toUpperCase(Locale.ROOT);
                if (!List.of("SUM", "COUNT", "AVG", "MIN", "MAX").contains(agg)) {
                    throw ApiException.badRequest("Aggregation '" + m.agg() + "' is not supported");
                }
                if ("COUNT".equals(agg)) {
                    if (m.field() == null || !"*".equals(m.field())) {
                        // COUNT can also be on a specific field (count non-null) — still must exist.
                        if (m.field() != null && ds.fields().get(m.field()) == null) {
                            throw ApiException.badRequest("Measure field '" + m.field() + "' unknown in " + ds.key());
                        }
                    }
                    continue;
                }
                FieldSpec f = ds.fields().get(m.field());
                if (f == null) {
                    throw ApiException.badRequest("Measure field '" + m.field() + "' is not a field of "
                            + ds.key());
                }
                if (!f.measure()) {
                    throw ApiException.badRequest("Field '" + m.field() + "' is not allowed as a measure");
                }
                if (f.type() == FieldSpec.Type.STRING || f.type() == FieldSpec.Type.ENUM) {
                    throw ApiException.badRequest("Cannot " + agg + " a "
                            + f.type() + " field (" + m.field() + ")");
                }
            }
        }
        if (def.filters() != null) {
            for (ReportDefinition.Filter f : def.filters()) {
                FieldSpec spec = ds.fields().get(f.field());
                if (spec == null) {
                    throw ApiException.badRequest("Filter field '" + f.field() + "' unknown in " + ds.key());
                }
                String op = f.op() == null ? "" : f.op().toUpperCase(Locale.ROOT);
                boolean numeric = spec.type() == FieldSpec.Type.NUMBER || spec.type() == FieldSpec.Type.INT;
                List<String> allowed = numeric
                        ? List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN")
                        : List.of("EQ", "NE", "IN", "NOT_IN");
                if (!allowed.contains(op)) {
                    throw ApiException.badRequest("Operator '" + f.op() + "' is not allowed on "
                            + spec.type() + " field " + f.field());
                }
            }
        }
        if (def.limit() != null && def.limit() < 0) {
            throw ApiException.badRequest("limit must be >= 0");
        }
    }

    // =============================================================== filter

    private List<Map<String, Object>> filter(List<Map<String, Object>> rows,
                                              List<ReportDefinition.Filter> filters, DatasetSpec ds) {
        if (filters.isEmpty()) return rows;
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        outer:
        for (Map<String, Object> row : rows) {
            for (ReportDefinition.Filter f : filters) {
                FieldSpec spec = ds.fields().get(f.field());
                Object v = row.get(f.field());
                if (!matches(v, f.op(), f.value(), spec)) continue outer;
            }
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private boolean matches(Object actual, String opRaw, Object expected, FieldSpec spec) {
        String op = opRaw == null ? "EQ" : opRaw.toUpperCase(Locale.ROOT);
        if (spec.type() == FieldSpec.Type.NUMBER || spec.type() == FieldSpec.Type.INT) {
            Double a = toDouble(actual);
            switch (op) {
                case "EQ": return Objects.equals(a, toDouble(expected));
                case "NE": return !Objects.equals(a, toDouble(expected));
                case "GT": return a != null && a > toDouble(expected);
                case "GTE": return a != null && a >= toDouble(expected);
                case "LT": return a != null && a < toDouble(expected);
                case "LTE": return a != null && a <= toDouble(expected);
                case "BETWEEN": {
                    if (!(expected instanceof List<?> list) || list.size() != 2) {
                        throw ApiException.badRequest("BETWEEN expects a 2-element list");
                    }
                    if (a == null) return false;
                    double lo = toDouble(list.get(0));
                    double hi = toDouble(list.get(1));
                    return a >= lo && a <= hi;
                }
                case "IN": {
                    if (!(expected instanceof List<?> list)) {
                        throw ApiException.badRequest("IN expects a list");
                    }
                    if (a == null) return false;
                    for (Object o : list) if (a.equals(toDouble(o))) return true;
                    return false;
                }
                default: throw ApiException.badRequest("Unknown numeric op " + op);
            }
        }
        // String/Enum
        String a = actual == null ? null : String.valueOf(actual);
        switch (op) {
            case "EQ": return Objects.equals(a, expected == null ? null : String.valueOf(expected));
            case "NE": return !Objects.equals(a, expected == null ? null : String.valueOf(expected));
            case "IN": {
                if (!(expected instanceof List<?> list)) {
                    throw ApiException.badRequest("IN expects a list");
                }
                for (Object o : list) if (Objects.equals(a, String.valueOf(o))) return true;
                return false;
            }
            case "NOT_IN": {
                if (!(expected instanceof List<?> list)) {
                    throw ApiException.badRequest("NOT_IN expects a list");
                }
                for (Object o : list) if (Objects.equals(a, String.valueOf(o))) return false;
                return true;
            }
            default: throw ApiException.badRequest("Unknown string op " + op);
        }
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    // =============================================================== aggregation

    private Accumulator[] newAccumulators(List<ReportDefinition.Measure> measures) {
        Accumulator[] acc = new Accumulator[measures.size()];
        for (int i = 0; i < measures.size(); i++) acc[i] = Accumulator.of(measures.get(i));
        return acc;
    }

    private Map<String, Object> grandTotals(List<Map<String, Object>> rows,
                                             List<ReportDefinition.Measure> measures) {
        Map<String, Object> totals = new LinkedHashMap<>();
        for (ReportDefinition.Measure m : measures) {
            Accumulator a = Accumulator.of(m);
            for (Map<String, Object> r : rows) a.accept(r);
            totals.put(m.as(), a.value());
        }
        return totals;
    }

    // =============================================================== sort

    private Comparator<List<Object>> comparatorFor(List<ReportDefinition.SortClause> sort, List<String> cols) {
        Comparator<List<Object>> c = null;
        for (ReportDefinition.SortClause s : sort) {
            int idx = cols.indexOf(s.by());
            if (idx < 0) throw ApiException.badRequest("Cannot sort by unknown column '" + s.by() + "'");
            boolean desc = "DESC".equalsIgnoreCase(s.dir());
            Comparator<List<Object>> step = (a, b) -> {
                Object x = a.get(idx);
                Object y = b.get(idx);
                int r = compareLoose(x, y);
                return desc ? -r : r;
            };
            c = c == null ? step : c.thenComparing(step);
        }
        return c;
    }

    private static int compareLoose(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private List<String> resultColumnKeys(List<String> dims, List<ReportDefinition.Measure> measures) {
        List<String> out = new ArrayList<>(dims.size() + measures.size());
        out.addAll(dims);
        for (ReportDefinition.Measure m : measures) out.add(m.as());
        return out;
    }

    // =============================================================== Accumulator

    private static abstract class Accumulator {
        abstract void accept(Map<String, Object> row);
        abstract Object value();

        static Accumulator of(ReportDefinition.Measure m) {
            String agg = m.agg() == null ? "COUNT" : m.agg().toUpperCase(Locale.ROOT);
            String field = m.field();
            return switch (agg) {
                case "SUM" -> new SumAcc(field);
                case "AVG" -> new AvgAcc(field);
                case "MIN" -> new MinMaxAcc(field, true);
                case "MAX" -> new MinMaxAcc(field, false);
                default -> new CountAcc(field);
            };
        }
    }

    private static class SumAcc extends Accumulator {
        final String field; double sum;
        SumAcc(String f) { this.field = f; }
        @Override void accept(Map<String, Object> row) {
            Double v = toDouble(row.get(field));
            if (v != null) sum += v;
        }
        @Override Object value() { return round6(sum); }
    }

    private static class AvgAcc extends Accumulator {
        final String field; double sum; long n;
        AvgAcc(String f) { this.field = f; }
        @Override void accept(Map<String, Object> row) {
            Double v = toDouble(row.get(field));
            if (v != null) { sum += v; n++; }
        }
        @Override Object value() { return n == 0 ? null : round6(sum / n); }
    }

    private static class MinMaxAcc extends Accumulator {
        final String field; final boolean min; Double cur;
        MinMaxAcc(String f, boolean min) { this.field = f; this.min = min; }
        @Override void accept(Map<String, Object> row) {
            Double v = toDouble(row.get(field));
            if (v == null) return;
            if (cur == null) cur = v;
            else cur = min ? Math.min(cur, v) : Math.max(cur, v);
        }
        @Override Object value() { return cur == null ? null : round6(cur); }
    }

    private static class CountAcc extends Accumulator {
        final String field; long n;
        CountAcc(String f) { this.field = f; }
        @Override void accept(Map<String, Object> row) {
            if (field == null || "*".equals(field)) { n++; return; }
            if (row.get(field) != null) n++;
        }
        @Override Object value() { return n; }
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    @SuppressWarnings("unused")
    private Collection<Map<String, Object>> debug() { return List.of(); }   // reserved
}
