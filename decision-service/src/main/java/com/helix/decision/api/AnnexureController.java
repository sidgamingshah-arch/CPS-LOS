package com.helix.decision.api;

import com.helix.decision.dto.AnnexureDtos.CreateRequest;
import com.helix.decision.dto.AnnexureDtos.NoteRequest;
import com.helix.decision.dto.AnnexureDtos.RejectRequest;
import com.helix.decision.dto.AnnexureDtos.SectionsRequest;
import com.helix.decision.entity.Annexure;
import com.helix.decision.service.AnnexureService;
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
 * The CAM-annexure engine. ONE master-driven authoring lifecycle for every annexure
 * type (CRI_SHEET / INDUSTRY_SCENARIO / ESG_ASSESSMENT / EXCHANGE_RISK /
 * PROJECT_DEFERMENT / GROUP_ANALYSIS), driven by the {@code ANNEXURE_TYPE} master.
 * Advisory authoring artefact — never mutates an authoritative figure. Every write
 * takes {@code X-Actor}; the gateway routes {@code /decision/...} here.
 */
@RestController
@RequestMapping("/api/annexures")
public class AnnexureController {

    private final AnnexureService service;

    public AnnexureController(AnnexureService service) {
        this.service = service;
    }

    @PostMapping
    public Annexure create(@Valid @RequestBody CreateRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "credit.analyst") String actor) {
        return service.create(req.annexureType(), req.subjectType(), req.subjectRef(), req.title(), actor);
    }

    @PutMapping("/{ref}/sections")
    public Annexure updateSections(@PathVariable String ref, @RequestBody(required = false) SectionsRequest req,
                                   @RequestHeader(value = "X-Actor", defaultValue = "credit.analyst") String actor) {
        boolean aiDraft = req != null && Boolean.TRUE.equals(req.aiDraft());
        return service.updateSections(ref, req == null ? null : req.sections(), aiDraft,
                req == null ? null : req.hint(), actor);
    }

    @PostMapping("/{ref}/submit")
    public Annexure submit(@PathVariable String ref,
                           @RequestHeader(value = "X-Actor", defaultValue = "credit.analyst") String actor) {
        return service.submit(ref, actor);
    }

    @PostMapping("/{ref}/review")
    public Annexure review(@PathVariable String ref, @RequestBody(required = false) NoteRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return service.review(ref, req == null ? null : req.notes(), actor);
    }

    @PostMapping("/{ref}/approve")
    public Annexure approve(@PathVariable String ref, @RequestBody(required = false) NoteRequest req,
                            @RequestHeader(value = "X-Actor", defaultValue = "credit.committee") String actor) {
        return service.approve(ref, req == null ? null : req.notes(), actor);
    }

    @PostMapping("/{ref}/reject")
    public Annexure reject(@PathVariable String ref, @RequestBody(required = false) RejectRequest req,
                           @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return service.reject(ref, req == null ? null : req.reason(), actor);
    }

    @GetMapping("/{ref}")
    public Annexure get(@PathVariable String ref) {
        return service.get(ref);
    }

    @GetMapping
    public List<Annexure> list(@RequestParam(required = false) String subjectRef,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) String type) {
        return service.list(subjectRef, status, type);
    }
}
