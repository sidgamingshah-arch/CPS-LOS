package com.helix.limit.api;

import com.helix.limit.entity.EodBatchRun;
import com.helix.limit.service.EodService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * EOD batch for limit management: FX rate refresh + currency revaluation +
 * utilisation reconciliation. Surface area for the ops desk (run, browse history,
 * view per-run revaluations and variances).
 */
@RestController
@RequestMapping("/api/limits/eod")
public class EodController {

    public record FxRefreshRequest(@NotBlank String currency, double rate) {
    }

    private final EodService eod;

    public EodController(EodService eod) {
        this.eod = eod;
    }

    @GetMapping("/fx")
    public Map<String, Object> fx() {
        return eod.fxView();
    }

    @PostMapping("/fx/refresh")
    public Map<String, Object> refreshFx(@RequestBody FxRefreshRequest req,
                                         @RequestHeader(value = "X-Actor", defaultValue = "market.data") String actor) {
        return eod.refreshFx(req.currency(), req.rate(), actor);
    }

    @PostMapping("/run")
    public EodBatchRun run(@RequestHeader(value = "X-Actor", defaultValue = "eod.batch") String actor) {
        return eod.runEod(actor);
    }

    @GetMapping("/runs")
    public List<EodBatchRun> runs() {
        return eod.runList();
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        return eod.runDetail(id);
    }
}
