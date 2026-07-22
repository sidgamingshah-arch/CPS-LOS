package com.helix.risk.api;

import com.helix.risk.dto.RiskNoteDtos.CreateRiskNoteRequest;
import com.helix.risk.dto.RiskNoteDtos.DecisionNoteRequest;
import com.helix.risk.dto.RiskNoteDtos.ReasonRequest;
import com.helix.risk.dto.RiskNoteDtos.ReassignRequest;
import com.helix.risk.dto.RiskNoteDtos.UpdateSectionsRequest;
import com.helix.risk.entity.RiskNote;
import com.helix.risk.service.RiskNoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Independent Risk Note API (/api/risk-notes) — the risk function's own governed
 * opinion record. Distinct from the advisory RAG overlay: a narrative opinion with its
 * own draft → submit → review → approve lifecycle (+ reassign / reject / reverse). The
 * acting identity is always the {@code X-Actor} header. A risk note never mutates the
 * authoritative rating.
 */
@RestController
@RequestMapping("/api/risk-notes")
public class RiskNoteController {

    private final RiskNoteService service;

    public RiskNoteController(RiskNoteService service) {
        this.service = service;
    }

    @PostMapping
    public RiskNote create(@Valid @RequestBody CreateRiskNoteRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return service.create(req, actor);
    }

    @PutMapping("/{ref}/sections")
    public RiskNote updateSections(@PathVariable String ref, @RequestBody UpdateSectionsRequest req,
                                   @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return service.updateSections(ref, req, actor);
    }

    @PostMapping("/{ref}/submit")
    public RiskNote submit(@PathVariable String ref,
                           @RequestHeader(value = "X-Actor", defaultValue = "risk.analyst") String actor) {
        return service.submit(ref, actor);
    }

    @PostMapping("/{ref}/review")
    public RiskNote review(@PathVariable String ref,
                           @RequestHeader(value = "X-Actor", defaultValue = "risk.reviewer") String actor) {
        return service.review(ref, actor);
    }

    @PostMapping("/{ref}/approve")
    public RiskNote approve(@PathVariable String ref, @RequestBody(required = false) DecisionNoteRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "risk.head") String actor) {
        return service.approve(ref, req == null ? null : req.note(), actor);
    }

    @PostMapping("/{ref}/reject")
    public RiskNote reject(@PathVariable String ref, @RequestBody(required = false) ReasonRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "risk.reviewer") String actor) {
        return service.reject(ref, req == null ? null : req.reason(), actor);
    }

    @PostMapping("/{ref}/reassign")
    public RiskNote reassign(@PathVariable String ref, @Valid @RequestBody ReassignRequest req,
                             @RequestHeader(value = "X-Actor", defaultValue = "risk.head") String actor) {
        return service.reassign(ref, req.toActor(), actor);
    }

    @PostMapping("/{ref}/reverse")
    public RiskNote reverse(@PathVariable String ref, @RequestBody(required = false) ReasonRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "risk.head") String actor) {
        return service.reverse(ref, req == null ? null : req.reason(), actor);
    }

    @GetMapping("/{ref}")
    public RiskNote get(@PathVariable String ref) {
        return service.get(ref);
    }

    @GetMapping
    public List<RiskNote> list(@RequestParam(required = false) String subjectRef) {
        return service.list(subjectRef);
    }
}
