package com.helix.portfolio.api;

import com.helix.portfolio.service.MisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Management-information / reports / dashboards (PRD §13). Read-only. */
@RestController
@RequestMapping("/api/mis")
public class MisController {

    private final MisService mis;

    public MisController(MisService mis) {
        this.mis = mis;
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
