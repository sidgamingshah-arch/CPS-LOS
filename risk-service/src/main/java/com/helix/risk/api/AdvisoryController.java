package com.helix.risk.api;

import com.helix.risk.dto.AdvisoryDtos.MacroScenarioRequest;
import com.helix.risk.entity.MacroImpactAssessment;
import com.helix.risk.entity.RagAssessment;
import com.helix.risk.service.AdvisoryRiskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Advisory risk overlays — statistical RAG scoring + macro directional impact. Both
 * are non-binding and never alter the authoritative deterministic rating.
 */
@RestController
@RequestMapping("/api/risk")
public class AdvisoryController {

    private final AdvisoryRiskService advisory;

    public AdvisoryController(AdvisoryRiskService advisory) {
        this.advisory = advisory;
    }

    @PostMapping("/{reference}/rag")
    public RagAssessment assessRag(@PathVariable String reference,
                                   @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return advisory.assessRag(reference, actor);
    }

    @GetMapping("/{reference}/rag")
    public List<RagAssessment> ragHistory(@PathVariable String reference) {
        return advisory.ragHistory(reference);
    }

    @PostMapping("/{reference}/macro-impact")
    public MacroImpactAssessment assessMacro(@PathVariable String reference,
                                             @RequestBody MacroScenarioRequest req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return advisory.assessMacro(reference, req, actor);
    }

    @GetMapping("/{reference}/macro-impact")
    public List<MacroImpactAssessment> macroHistory(@PathVariable String reference) {
        return advisory.macroHistory(reference);
    }
}
