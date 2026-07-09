package com.helix.portfolio.api;

import com.helix.common.rbac.ActorDirectory;
import com.helix.common.rbac.ProtectedAction;
import com.helix.common.web.ApiException;
import com.helix.portfolio.reports.DatasetRegistry;
import com.helix.portfolio.reports.DatasetSpec;
import com.helix.portfolio.reports.FieldSpec;
import com.helix.portfolio.reports.ReportDefinition;
import com.helix.portfolio.reports.ReportDefinitionClient;
import com.helix.portfolio.reports.ReportEngine;
import com.helix.portfolio.reports.ReportResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-service report builder API. Read-only by design: the engine aggregates
 * the system-of-record book, it never mutates a figure. Saved definitions live
 * in the {@code REPORT_DEFINITION} master (maker-checker, versioned, SoD).
 */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    private final ReportEngine engine;
    private final DatasetRegistry registry;
    private final ReportDefinitionClient definitions;
    /** Optional — when absent we skip the role check (matches limit/risk service behaviour). */
    private final ActorDirectory roles;

    public ReportsController(ReportEngine engine, DatasetRegistry registry,
                             ReportDefinitionClient definitions,
                             @Autowired(required = false) ActorDirectory roles) {
        this.engine = engine;
        this.registry = registry;
        this.definitions = definitions;
        this.roles = roles;
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DatasetSpec d : registry.all()) {
            Map<String, Object> ds = new LinkedHashMap<>();
            ds.put("key", d.key());
            ds.put("label", d.label());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (FieldSpec f : d.fields().values()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", f.name());
                entry.put("type", f.type().name());
                entry.put("dimension", f.dimension());
                entry.put("measure", f.measure());
                if (!f.enumValues().isEmpty()) entry.put("enumValues", f.enumValues());
                fields.add(entry);
            }
            ds.put("fields", fields);
            ds.put("aggregations", List.of("SUM", "COUNT", "AVG", "MIN", "MAX"));
            ds.put("stringOps", List.of("EQ", "NE", "IN", "NOT_IN"));
            ds.put("numberOps", List.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IN"));
            out.add(ds);
        }
        return out;
    }

    @PostMapping("/run")
    public ReportResult run(@RequestBody ReportDefinition def,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        requireActor(actor);
        if (roles != null) roles.require(actor, ProtectedAction.REPORT_RUN);
        return engine.run(def, actor);
    }

    @GetMapping("/{key}/run")
    public ReportResult runSaved(@PathVariable("key") String reportKey,
                                  @RequestHeader(value = "X-Actor", required = false) String actor) {
        requireActor(actor);
        if (roles != null) roles.require(actor, ProtectedAction.REPORT_RUN);
        ReportDefinition def = definitions.load(reportKey);
        return engine.run(def, actor);
    }

    private void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw ApiException.forbiddenAutonomy(
                    "A named actor (X-Actor header) is required to run a report");
        }
    }
}
