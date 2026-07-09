package com.helix.portfolio.api;

import com.helix.portfolio.entity.ExportBatch;
import com.helix.portfolio.service.ExportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Outbound canonical export feeds — ERM (obligor risk), Finance/GL (provision
 * entries), CPR (portfolio composition). Idempotent per as-of day; each batch holds
 * the full canonical envelope and is examiner-retrievable.
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @PostMapping("/erm")
    public ExportBatch erm(@RequestHeader(value = "X-Actor", defaultValue = "export.batch") String actor) {
        return exports.generateErm(actor);
    }

    @PostMapping("/finance-gl")
    public ExportBatch financeGl(@RequestHeader(value = "X-Actor", defaultValue = "export.batch") String actor) {
        return exports.generateFinanceGl(actor);
    }

    @PostMapping("/cpr")
    public ExportBatch cpr(@RequestHeader(value = "X-Actor", defaultValue = "export.batch") String actor) {
        return exports.generateCpr(actor);
    }

    /** RBI CRILC large-credit feed — borrowers in SMA/NPA at/above the reporting threshold. */
    @PostMapping("/crilc")
    public ExportBatch crilc(@RequestHeader(value = "X-Actor", defaultValue = "export.batch") String actor) {
        return exports.generateCrilc(actor);
    }

    @GetMapping("/batches")
    public List<ExportBatch> batches(@RequestParam(required = false) String destination) {
        return exports.list(destination);
    }

    @GetMapping("/batches/{id}")
    public ExportBatch batch(@PathVariable Long id) {
        return exports.get(id);
    }
}
