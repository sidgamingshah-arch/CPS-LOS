package com.helix.portfolio.api;

import com.helix.portfolio.service.CustomerPortfolio360Service;
import com.helix.portfolio.service.MisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Management-information / reports / dashboards (PRD §13). Read-only. */
@RestController
@RequestMapping("/api/mis")
public class MisController {

    private final MisService mis;
    private final CustomerPortfolio360Service threeSixty;

    public MisController(MisService mis, CustomerPortfolio360Service threeSixty) {
        this.mis = mis;
        this.threeSixty = threeSixty;
    }

    @GetMapping("/customer360/{reference}")
    public Map<String, Object> customer360(@PathVariable String reference) {
        return threeSixty.customer360(reference);
    }

    @GetMapping("/portfolio360")
    public Map<String, Object> portfolio360(@RequestParam(required = false) String rm) {
        return threeSixty.portfolio360(rm);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return mis.dashboard();
    }

    @GetMapping("/composition")
    public Map<String, Object> composition() {
        return mis.bookComposition();
    }

    @GetMapping("/raroc-variance")
    public Map<String, Object> rarocVariance() {
        return mis.rarocVariance();
    }

    @GetMapping("/pipeline-ageing")
    public Map<String, Object> pipelineAgeing() {
        return mis.pipelineAgeing();
    }

    @GetMapping("/ecl-by-stage")
    public Map<String, Object> eclByStage() {
        return mis.eclByStage();
    }

    @GetMapping("/watchlist")
    public Map<String, Object> watchlist() {
        return mis.watchlistSummary();
    }
}
