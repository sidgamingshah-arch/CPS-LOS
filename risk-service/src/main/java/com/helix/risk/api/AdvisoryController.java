package com.helix.risk.api;

import com.helix.risk.dto.AdvisoryDtos.MacroScenarioRequest;
import com.helix.risk.dto.OptimiserDtos.OptimisationResult;
import com.helix.risk.dto.OptimiserDtos.OptimiseRequest;
import com.helix.risk.dto.OptimiserDtos.PricingExceptionDecision;
import com.helix.risk.dto.OptimiserDtos.PricingExceptionRequest;
import com.helix.risk.entity.MacroImpactAssessment;
import com.helix.risk.entity.PricingException;
import com.helix.risk.entity.RagAssessment;
import com.helix.risk.service.AdvisoryRiskService;
import com.helix.risk.service.PricingExceptionService;
import com.helix.risk.service.PricingOptimiser;
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
    private final PricingOptimiser optimiser;
    private final PricingExceptionService exceptions;

    public AdvisoryController(AdvisoryRiskService advisory, PricingOptimiser optimiser,
                             PricingExceptionService exceptions) {
        this.advisory = advisory;
        this.optimiser = optimiser;
        this.exceptions = exceptions;
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

    @PostMapping("/{reference}/pricing/optimise")
    public OptimisationResult optimise(@PathVariable String reference, @RequestBody OptimiseRequest req,
                                       @RequestHeader(value = "X-Actor", defaultValue = "pricing.analyst") String actor) {
        return optimiser.optimise(reference, req, actor);
    }

    @PostMapping("/{reference}/pricing/exception")
    public PricingException proposeException(@PathVariable String reference, @RequestBody PricingExceptionRequest req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return exceptions.propose(reference, req.proposedRate() == null ? 0 : req.proposedRate(), req.reason(), actor);
    }

    @GetMapping("/{reference}/pricing/exception")
    public List<PricingException> listExceptions(@PathVariable String reference) {
        return exceptions.list(reference);
    }

    @GetMapping("/pricing/exception/pending")
    public List<PricingException> pendingExceptions() {
        return exceptions.pending();
    }

    @PostMapping("/pricing/exception/{id}/decision")
    public PricingException decideException(@PathVariable Long id, @RequestBody PricingExceptionDecision req,
                                            @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return exceptions.decide(id, req.approve(), req.comment(), actor);
    }
}
