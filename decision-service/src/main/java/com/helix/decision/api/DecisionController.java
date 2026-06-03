package com.helix.decision.api;

import com.helix.decision.dto.Dtos.AddCovenantRequest;
import com.helix.decision.dto.Dtos.DecisionRequest;
import com.helix.decision.entity.Covenant;
import com.helix.decision.entity.CreditDecision;
import com.helix.decision.service.CovenantService;
import com.helix.decision.service.DecisionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final DecisionService decisions;
    private final CovenantService covenants;

    public DecisionController(DecisionService decisions, CovenantService covenants) {
        this.decisions = decisions;
        this.covenants = covenants;
    }

    @PostMapping("/{reference}/route")
    public CreditDecision route(@PathVariable String reference,
                                @RequestHeader(value = "X-Actor", defaultValue = "credit.ops") String actor) {
        return decisions.initiate(reference, actor);
    }

    @PostMapping("/{reference}/decide")
    public CreditDecision decide(@PathVariable String reference, @Valid @RequestBody DecisionRequest req,
                                 @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return decisions.decide(reference, req, actor);
    }

    @GetMapping("/{reference}")
    public CreditDecision latest(@PathVariable String reference) {
        return decisions.latest(reference);
    }

    @GetMapping("/{reference}/history")
    public List<CreditDecision> history(@PathVariable String reference) {
        return decisions.history(reference);
    }

    @GetMapping("/{reference}/committee-note")
    public Map<String, String> committeeNote(@PathVariable String reference) {
        return decisions.committeeNote(reference);
    }

    // ---- covenants ----

    @PostMapping("/{reference}/covenants")
    public Covenant addCovenant(@PathVariable String reference, @Valid @RequestBody AddCovenantRequest req,
                                @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        return covenants.add(reference, req, actor);
    }

    @GetMapping("/{reference}/covenants")
    public List<Covenant> covenants(@PathVariable String reference) {
        return covenants.list(reference);
    }

    @GetMapping("/{reference}/covenants/suggest")
    public List<AddCovenantRequest> suggest(@RequestParam(defaultValue = "BBB") String grade) {
        return covenants.suggest(grade.toUpperCase());
    }

    @DeleteMapping("/covenants/{id}")
    public void deactivate(@PathVariable Long id,
                           @RequestHeader(value = "X-Actor", defaultValue = "analyst.user") String actor) {
        covenants.deactivate(id, actor);
    }
}
