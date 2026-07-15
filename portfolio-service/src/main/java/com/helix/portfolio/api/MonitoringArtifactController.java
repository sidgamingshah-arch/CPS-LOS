package com.helix.portfolio.api;

import com.helix.portfolio.entity.MonitoringArtifact;
import com.helix.portfolio.service.MonitoringArtifactService;
import jakarta.validation.constraints.NotBlank;
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
import java.util.Map;

/**
 * The monitoring-artifact engine (post-disbursement). ONE lifecycle for every
 * artifact type (CALL_MEMO / PLANT_VISIT / LCR / QPR / BROKER_REVIEW / STOCK_AUDIT /
 * AUDIT_NOTE), driven by the {@code MONITORING_ARTIFACT_TYPE} master. Records /
 * advisory — never mutates an authoritative figure (ECL / IRAC / exposure). Every
 * write takes {@code X-Actor}; the gateway routes {@code /monitoring/...} here.
 */
@RestController
@RequestMapping("/api/monitoring/artifacts")
public class MonitoringArtifactController {

    private final MonitoringArtifactService service;

    public MonitoringArtifactController(MonitoringArtifactService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank String artifactType, String subjectType, String subjectRef,
                                String title) {
    }

    public record SectionsRequest(Map<String, Object> sections) {
    }

    public record NoteRequest(String notes) {
    }

    public record VendorRfqRequest(@NotBlank String vendorId, String question) {
    }

    @PostMapping
    public MonitoringArtifact create(@RequestBody CreateRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.create(req.artifactType(), req.subjectType(), req.subjectRef(), req.title(), actor);
    }

    @PutMapping("/{ref}/sections")
    public MonitoringArtifact updateSections(@PathVariable String ref, @RequestBody SectionsRequest req,
                                             @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.updateSections(ref, req == null ? null : req.sections(), actor);
    }

    @PostMapping("/{ref}/submit")
    public MonitoringArtifact submit(@PathVariable String ref,
                                     @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.submit(ref, actor);
    }

    @PostMapping("/{ref}/review")
    public MonitoringArtifact review(@PathVariable String ref, @RequestBody(required = false) NoteRequest req,
                                     @RequestHeader(value = "X-Actor", defaultValue = "credit.officer") String actor) {
        return service.review(ref, req == null ? null : req.notes(), actor);
    }

    @PostMapping("/{ref}/approve")
    public MonitoringArtifact approve(@PathVariable String ref, @RequestBody(required = false) NoteRequest req,
                                      @RequestHeader(value = "X-Actor", defaultValue = "credit.committee") String actor) {
        return service.approve(ref, req == null ? null : req.notes(), actor);
    }

    @PostMapping("/{ref}/authorize")
    public MonitoringArtifact authorize(@PathVariable String ref, @RequestBody(required = false) NoteRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "cro") String actor) {
        return service.authorize(ref, req == null ? null : req.notes(), actor);
    }

    @PostMapping("/{ref}/vendor-rfq")
    public MonitoringArtifact vendorRfq(@PathVariable String ref, @RequestBody VendorRfqRequest req,
                                        @RequestHeader(value = "X-Actor", defaultValue = "portfolio.manager") String actor) {
        return service.vendorRfq(ref, req.vendorId(), req.question(), actor);
    }

    @GetMapping("/{ref}")
    public MonitoringArtifactService.View get(@PathVariable String ref) {
        return service.view(ref);
    }

    @GetMapping
    public List<MonitoringArtifact> list(@RequestParam(required = false) String subjectRef,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String type) {
        return service.list(subjectRef, status, type);
    }
}
