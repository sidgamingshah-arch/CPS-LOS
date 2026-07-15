package com.helix.decision.api;

import com.helix.decision.dto.SrmDtos.CreateSrmRequest;
import com.helix.decision.dto.SrmDtos.MarkItemRequest;
import com.helix.decision.entity.SrmReview;
import com.helix.decision.service.SrmService;
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
 * SRM (Structured Review / renewal) — a renewal decision built ON the Noting engine.
 * Creating a review materialises the SRM_CHECKLIST and raises a linked SRM_RENEWAL
 * noting; the governed approval runs through /api/notings. When the noting is observed
 * AUTHORIZED, the subject's MER next-review date is advanced. A review is a record only.
 */
@RestController
@RequestMapping("/api/srm")
public class SrmController {

    private final SrmService srm;

    public SrmController(SrmService srm) {
        this.srm = srm;
    }

    @PostMapping("/reviews")
    public SrmReview create(@Valid @RequestBody CreateSrmRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return srm.create(req, actor);
    }

    @GetMapping("/reviews")
    public List<SrmReview> list(@RequestParam(required = false) String subjectRef) {
        return srm.list(subjectRef);
    }

    @GetMapping("/reviews/{id}")
    public SrmReview get(@PathVariable Long id) {
        return srm.get(id);
    }

    @PostMapping("/reviews/{id}/checklist/{code}")
    public SrmReview markItem(@PathVariable Long id, @PathVariable String code,
                              @RequestBody(required = false) MarkItemRequest req,
                              @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        boolean done = req == null || req.done() == null || req.done();
        return srm.markItem(id, code, done, actor);
    }

    @PostMapping("/reviews/{id}/submit-noting")
    public SrmReview submitNoting(@PathVariable Long id,
                                  @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return srm.submitNoting(id, actor);
    }

    @PostMapping("/reviews/{id}/refresh")
    public SrmReview refresh(@PathVariable Long id,
                             @RequestHeader(value = "X-Actor", defaultValue = "rm.user") String actor) {
        return srm.refresh(id, actor);
    }
}
