package com.helix.risk.api;

import com.helix.risk.dto.QualitativeDtos.ConfirmRequest;
import com.helix.risk.dto.QualitativeDtos.QualitativeView;
import com.helix.risk.service.QualitativeAssessmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Qualitative scorecard API — advisory, prompt-driven scoring of the qualitative
 * rating parameters. {@code assess} recommends scores (grounded on deal data via the
 * QUAL_SCORECARD prompts); each parameter is human-confirmed; the authoritative grade
 * is never mutated by this path.
 */
@RestController
@RequestMapping("/api/risk")
public class QualitativeController {

    private final QualitativeAssessmentService qualitative;

    public QualitativeController(QualitativeAssessmentService qualitative) {
        this.qualitative = qualitative;
    }

    @PostMapping("/{reference}/qualitative/assess")
    public QualitativeView assess(@PathVariable String reference,
                                  @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return qualitative.assess(reference, actor);
    }

    @GetMapping("/{reference}/qualitative")
    public QualitativeView get(@PathVariable String reference) {
        return qualitative.get(reference);
    }

    @PostMapping("/qualitative/{id}/confirm")
    public QualitativeView confirm(@PathVariable Long id,
                                   @RequestBody(required = false) ConfirmRequest req,
                                   @RequestHeader("X-Actor") String actor) {
        return qualitative.confirm(id, req == null ? null : req.score(),
                req == null ? null : req.note(), actor);
    }
}
