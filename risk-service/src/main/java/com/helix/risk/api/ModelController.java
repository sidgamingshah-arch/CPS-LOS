package com.helix.risk.api;

import com.helix.risk.dto.ModelDtos.AnswerRequest;
import com.helix.risk.dto.ModelDtos.ModelView;
import com.helix.risk.service.ModelEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configurable scoring-model API — resolves the right model for a deal, renders
 * the typed questionnaire (visibility rules, master-driven options, iterative
 * groups), captures answers, scores a weighted composite, and human-confirms.
 * Advisory throughout: the composite never moves the authoritative grade.
 */
@RestController
@RequestMapping("/api/risk")
public class ModelController {

    private final ModelEngine engine;

    public ModelController(ModelEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/{reference}/model")
    public ModelView render(@PathVariable String reference) {
        return engine.render(reference);
    }

    @PostMapping("/{reference}/model/resolve")
    public ModelView resolve(@PathVariable String reference,
                             @RequestParam(required = false) String sector) {
        return engine.resolve(reference, sector);
    }

    @PostMapping("/{reference}/model/answer")
    public ModelView answer(@PathVariable String reference,
                            @RequestBody AnswerRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return engine.answer(reference, req == null ? null : req.answers(), "HUMAN", actor);
    }

    @PostMapping("/{reference}/model/suggest")
    public ModelView suggest(@PathVariable String reference,
                             @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return engine.suggest(reference, actor);
    }

    @PostMapping("/{reference}/model/confirm")
    public ModelView confirm(@PathVariable String reference,
                             @RequestHeader("X-Actor") String actor) {
        return engine.confirm(reference, actor);
    }
}
