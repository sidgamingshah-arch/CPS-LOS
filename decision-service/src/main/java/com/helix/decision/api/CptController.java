package com.helix.decision.api;

import com.helix.decision.dto.CptDtos.GenerateCptRequest;
import com.helix.decision.dto.CptDtos.ReviewCptRequest;
import com.helix.decision.entity.ClientPlanningTemplate;
import com.helix.decision.service.ClientPlanningTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Client Planning Template endpoints (advisory; RM-confirmed). One CPT per
 * counterparty per generation, versioned. The deterministic figure path
 * (grade / capital / pricing) is not mutated by these endpoints.
 */
@RestController
@RequestMapping("/api/cpt")
public class CptController {

    private final ClientPlanningTemplateService cpt;

    public CptController(ClientPlanningTemplateService cpt) {
        this.cpt = cpt;
    }

    @PostMapping("/{counterpartyReference}/generate")
    public ClientPlanningTemplate generate(@PathVariable String counterpartyReference,
                                           @RequestBody(required = false) GenerateCptRequest req,
                                           @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        Double trend = req == null ? null : req.trendFactorOverride();
        return cpt.generate(counterpartyReference, trend, actor);
    }

    @GetMapping("/{counterpartyReference}")
    public ClientPlanningTemplate latest(@PathVariable String counterpartyReference) {
        return cpt.latest(counterpartyReference);
    }

    @GetMapping("/{counterpartyReference}/versions")
    public List<ClientPlanningTemplate> versions(@PathVariable String counterpartyReference) {
        return cpt.versions(counterpartyReference);
    }

    @PostMapping("/templates/{id}/review")
    public ClientPlanningTemplate review(@PathVariable Long id,
                                         @RequestBody ReviewCptRequest req,
                                         @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return cpt.review(id, req.approve(), req.note(), actor);
    }
}
