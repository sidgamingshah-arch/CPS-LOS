package com.helix.origination.api;

import com.helix.origination.dto.CollateralIntelDtos.ConfirmCollateralExtractionRequest;
import com.helix.origination.dto.CollateralIntelDtos.ExtractCollateralRequest;
import com.helix.origination.dto.CollateralIntelDtos.ReviewRequest;
import com.helix.origination.dto.CollateralIntelDtos.RevalueRequest;
import com.helix.origination.entity.Collateral;
import com.helix.origination.entity.CollateralExtraction;
import com.helix.origination.entity.CollateralRevaluation;
import com.helix.origination.service.CollateralIntelligenceService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Type-aware collateral intelligence + LTV revaluation + charge-Excel — all
 * advisory; the deterministic capital path is untouched.
 */
@RestController
@RequestMapping("/api/collateral-intel")
public class CollateralIntelController {

    private final CollateralIntelligenceService intel;

    public CollateralIntelController(CollateralIntelligenceService intel) {
        this.intel = intel;
    }

    // ---- extraction (per collateral type) ----

    @PostMapping("/{reference}/extract")
    public CollateralExtraction extract(@PathVariable String reference,
                                        @Valid @RequestBody ExtractCollateralRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.extract(reference, req.documentKind(), req.text(), actor);
    }

    @GetMapping("/{reference}/extractions")
    public List<CollateralExtraction> list(@PathVariable String reference) {
        return intel.list(reference);
    }

    @PostMapping("/extractions/{id}/confirm")
    public Collateral confirm(@PathVariable Long id,
                              @RequestBody(required = false) ConfirmCollateralExtractionRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.confirm(id, req, actor);
    }

    @PostMapping("/extractions/{id}/reject")
    public CollateralExtraction reject(@PathVariable Long id,
                                       @RequestBody(required = false) ReviewRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.reject(id, req == null ? null : req.note(), actor);
    }

    // ---- revaluation + LTV alerts ----

    @PostMapping("/collaterals/{collateralId}/revalue")
    public CollateralRevaluation revalue(@PathVariable Long collateralId,
                                         @Valid @RequestBody RevalueRequest req,
                                         @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.revalue(collateralId, req, actor);
    }

    @PostMapping("/revaluations/{revaluationId}/review")
    public CollateralRevaluation review(@PathVariable Long revaluationId,
                                        @Valid @RequestBody ReviewRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return intel.review(revaluationId, req.apply(), req.note(), actor);
    }

    @GetMapping("/{reference}/revaluations")
    public List<CollateralRevaluation> revaluations(@PathVariable String reference) {
        return intel.revaluationsFor(reference);
    }

    // ---- charge-Excel (CSV; Excel-compatible) ----

    @GetMapping(value = "/{reference}/charge-excel", produces = "text/csv")
    public ResponseEntity<String> chargeExcel(@PathVariable String reference) {
        String csv = intel.chargeExcel(reference);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"charge-" + reference + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }
}
