package com.helix.portfolio.api;

import com.helix.portfolio.dto.GlobalCashflowDtos.AssembleRequest;
import com.helix.portfolio.entity.GlobalCashflowAssessment;
import com.helix.portfolio.service.GlobalCashflowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Global / combined cash-flow (relationship consolidated debt-service). Consolidates each
 * group member's latest confirmed spread figures into a combined coverage view + per-member
 * contribution list. Deterministic + read-only: it never mutates a member's authoritative
 * spread / rating / exposure. The gateway routes {@code /portfolio/api/portfolio/global-cashflow/...}
 * here; every write takes {@code X-Actor}.
 */
@RestController
@RequestMapping("/api/portfolio/global-cashflow")
public class GlobalCashflowController {

    private final GlobalCashflowService service;

    public GlobalCashflowController(GlobalCashflowService service) {
        this.service = service;
    }

    @PostMapping
    public GlobalCashflowAssessment assemble(@RequestBody AssembleRequest req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.assemble(req == null ? null : req.groupReference(), actor);
    }

    @GetMapping
    public List<GlobalCashflowAssessment> list(@RequestParam(required = false) String groupReference) {
        return service.list(groupReference);
    }

    @GetMapping("/{gcfRef}")
    public GlobalCashflowAssessment get(@PathVariable String gcfRef) {
        return service.get(gcfRef);
    }
}
