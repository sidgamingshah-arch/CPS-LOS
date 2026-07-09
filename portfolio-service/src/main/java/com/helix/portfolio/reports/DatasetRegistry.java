package com.helix.portfolio.reports;

import com.helix.common.web.ApiException;
import com.helix.portfolio.entity.EwsSignal;
import com.helix.portfolio.entity.ExposureRecord;
import com.helix.portfolio.entity.RarocTracking;
import com.helix.portfolio.repo.EwsSignalRepository;
import com.helix.portfolio.repo.ExposureRecordRepository;
import com.helix.portfolio.repo.RarocTrackingRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whitelist. Every queryable dataset is declared here in code with a typed
 * field list and a deterministic projection from its source repository. Nothing
 * outside this registry is reachable from the reporting engine — this is the
 * SQL-injection / data-exposure boundary.
 */
@Component
public class DatasetRegistry {

    private final ExposureRecordRepository exposures;
    private final RarocTrackingRepository raroc;
    private final EwsSignalRepository signals;
    private final Map<String, DatasetSpec> specs = new LinkedHashMap<>();

    public DatasetRegistry(ExposureRecordRepository exposures, RarocTrackingRepository raroc,
                           EwsSignalRepository signals) {
        this.exposures = exposures;
        this.raroc = raroc;
        this.signals = signals;
        register();
    }

    public Set<String> keys() { return new LinkedHashSet<>(specs.keySet()); }

    public List<DatasetSpec> all() { return List.copyOf(specs.values()); }

    public DatasetSpec require(String key) {
        DatasetSpec s = specs.get(key);
        if (s == null) {
            throw ApiException.badRequest("Unknown dataset '" + key + "' — allowed: " + keys());
        }
        return s;
    }

    private void register() {
        specs.put("EXPOSURE_BOOK", DatasetSpec.of(
                "EXPOSURE_BOOK", "Booked exposures (the lending book)",
                List.of(
                        FieldSpec.dim("counterpartyName", FieldSpec.Type.STRING),
                        FieldSpec.dim("jurisdiction", FieldSpec.Type.STRING),
                        FieldSpec.dim("segment", FieldSpec.Type.STRING),
                        FieldSpec.dim("sector", FieldSpec.Type.STRING),
                        FieldSpec.dim("facilityType", FieldSpec.Type.STRING),
                        FieldSpec.dim("finalGrade", FieldSpec.Type.STRING),
                        FieldSpec.dim("status", FieldSpec.Type.STRING),
                        FieldSpec.dim("currency", FieldSpec.Type.STRING),
                        FieldSpec.dim("tenorBucket", FieldSpec.Type.STRING),
                        FieldSpec.dimAndMetric("tenorMonths", FieldSpec.Type.INT),
                        FieldSpec.dimAndMetric("daysPastDue", FieldSpec.Type.INT),
                        FieldSpec.metric("ead", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("rwa", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("capitalRequired", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("pd", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("lgd", FieldSpec.Type.NUMBER)),
                () -> {
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    for (ExposureRecord e : exposures.findAll()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("counterpartyName", e.getCounterpartyName());
                        row.put("jurisdiction", e.getJurisdiction());
                        row.put("segment", e.getSegment());
                        row.put("sector", e.getSector());
                        row.put("facilityType", e.getFacilityType());
                        row.put("finalGrade", e.getFinalGrade());
                        row.put("status", e.getStatus());
                        row.put("currency", e.getCurrency());
                        row.put("tenorMonths", e.getTenorMonths());
                        row.put("tenorBucket", bucketTenor(e.getTenorMonths()));
                        row.put("daysPastDue", e.getDaysPastDue());
                        row.put("ead", e.getEad());
                        row.put("rwa", e.getRwa());
                        row.put("capitalRequired", e.getCapitalRequired());
                        row.put("pd", e.getPd());
                        row.put("lgd", e.getLgd());
                        out.add(row);
                    }
                    return out;
                }));

        specs.put("RAROC_TRACKING", DatasetSpec.of(
                "RAROC_TRACKING", "Projected-vs-actual RAROC tracking",
                List.of(
                        FieldSpec.dim("applicationReference", FieldSpec.Type.STRING),
                        FieldSpec.dim("origination", FieldSpec.Type.STRING),
                        FieldSpec.metric("projectedRaroc", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("actualRaroc", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("variance", FieldSpec.Type.NUMBER),
                        FieldSpec.metric("absVariancePct", FieldSpec.Type.NUMBER)),
                () -> {
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    for (RarocTracking t : raroc.findAll()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("applicationReference", t.getApplicationReference());
                        row.put("origination", t.isOrigination() ? "PROJECTED" : "ACTUAL");
                        row.put("projectedRaroc", t.getProjectedRaroc());
                        row.put("actualRaroc", t.getActualRaroc());
                        row.put("variance", t.getVariance());
                        row.put("absVariancePct", t.getAbsVariancePct());
                        out.add(row);
                    }
                    return out;
                }));

        specs.put("EWS_SIGNALS", DatasetSpec.of(
                "EWS_SIGNALS", "Early-warning signals",
                List.of(
                        FieldSpec.dim("signalType", FieldSpec.Type.STRING),
                        FieldSpec.dim("severity", FieldSpec.Type.STRING),
                        FieldSpec.dim("counterpartyRef", FieldSpec.Type.STRING)),
                () -> {
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    for (EwsSignal e : signals.findAll()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("signalType", e.getSignalType());
                        row.put("severity", e.getSeverity());
                        row.put("counterpartyRef", e.getCounterpartyRef());
                        out.add(row);
                    }
                    return out;
                }));
    }

    private static String bucketTenor(Integer months) {
        if (months == null) return "(unknown)";
        if (months <= 12) return "0-12";
        if (months <= 36) return "13-36";
        if (months <= 60) return "37-60";
        return "60+";
    }
}
