package com.helix.portfolio.reports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A dataset is a named projection of one repository into a list of typed,
 * whitelisted rows. The projection function is the only access point — the
 * engine never reflects on entity fields or builds dynamic queries from user
 * input. Adding a new dataset = one entry in the registry.
 */
public record DatasetSpec(
        String key,
        String label,
        Map<String, FieldSpec> fields,
        Supplier<List<Map<String, Object>>> rows
) {
    public static DatasetSpec of(String key, String label, List<FieldSpec> fields,
                                  Supplier<List<Map<String, Object>>> rows) {
        Map<String, FieldSpec> byName = new LinkedHashMap<>();
        for (FieldSpec f : fields) byName.put(f.name(), f);
        return new DatasetSpec(key, label, byName, rows);
    }
}
