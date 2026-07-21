package com.helix.risk.api;

import com.helix.risk.dto.RiskDtos.OverrideRatingRequest;
import com.helix.risk.dto.RiskDtos.OverrideStats;
import com.helix.risk.dto.RiskDtos.RiskSummary;
import com.helix.risk.entity.CapitalResult;
import com.helix.risk.entity.PricingResult;
import com.helix.risk.entity.Rating;
import com.helix.risk.service.RiskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskService risk;

    public RiskController(RiskService risk) {
        this.risk = risk;
    }

    // ---- rating ----

    @PostMapping("/{reference}/rate")
    public Rating rate(@PathVariable String reference,
                       @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return risk.rate(reference, actor);
    }

    @PostMapping("/{reference}/rating/override")
    public Rating override(@PathVariable String reference, @Valid @RequestBody OverrideRatingRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return risk.overrideRating(reference, req, actor);
    }

    @PostMapping("/{reference}/rating/confirm")
    public Rating confirmRating(@PathVariable String reference,
                                @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return risk.confirmRating(reference, actor);
    }

    @GetMapping("/{reference}/rating")
    public Rating rating(@PathVariable String reference) {
        return risk.latestRating(reference);
    }

    /** Configurable, parameter-routed scoring-approval state for the latest rating. */
    @GetMapping("/{reference}/scoring-approval")
    public Map<String, Object> scoringApproval(@PathVariable String reference) {
        return risk.scoringApproval(reference);
    }

    @GetMapping("/override-stats")
    public OverrideStats overrideStats(@RequestParam String segment) {
        return risk.overrideStats(segment);
    }

    // ---- capital ----

    @PostMapping("/{reference}/capital")
    public CapitalResult computeCapital(@PathVariable String reference,
                                        @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return risk.computeCapital(reference, actor);
    }

    @GetMapping("/{reference}/capital/explain")
    public Map<String, String> explainCapital(@PathVariable String reference) {
        return Map.of("explanation", risk.explainCapital(reference));
    }

    // ---- pricing ----

    @PostMapping("/{reference}/pricing")
    public PricingResult price(@PathVariable String reference,
                               @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return risk.price(reference, actor);
    }

    // ---- summary ----

    @GetMapping("/{reference}")
    public RiskSummary summary(@PathVariable String reference) {
        return risk.summary(reference);
    }
}
