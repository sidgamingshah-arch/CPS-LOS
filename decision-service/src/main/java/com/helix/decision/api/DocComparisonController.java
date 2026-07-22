package com.helix.decision.api;

import com.helix.decision.dto.DocCompareDtos.CompareRequest;
import com.helix.decision.dto.DocCompareDtos.ComparisonView;
import com.helix.decision.service.DocComparisonService;
import jakarta.validation.Valid;
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
 * Document-comparison API (CLoM F57). Computes a deterministic, read-only structured change
 * table between two versioned artifacts (two credit-proposal versions or two generated-document
 * versions). The engine never mutates either source.
 */
@RestController
@RequestMapping("/api/doc-compare")
public class DocComparisonController {

    private final DocComparisonService service;

    public DocComparisonController(DocComparisonService service) {
        this.service = service;
    }

    /** Compute + persist a comparison and return the change table. */
    @PostMapping
    public ComparisonView compare(@Valid @RequestBody CompareRequest req,
                                  @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return service.compare(req.kind(), req.subjectRef(), req.leftRef(), req.rightRef(), actor);
    }

    /** All comparisons for a subject (newest first). */
    @GetMapping
    public List<ComparisonView> list(@RequestParam String subjectRef) {
        return service.listBySubject(subjectRef);
    }

    @GetMapping("/{comparisonRef}")
    public ComparisonView get(@PathVariable String comparisonRef) {
        return service.get(comparisonRef);
    }
}
