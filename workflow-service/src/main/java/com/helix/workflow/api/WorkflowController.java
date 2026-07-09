package com.helix.workflow.api;

import com.helix.common.web.ApiException;
import com.helix.workflow.entity.WorkflowInstance;
import com.helix.workflow.entity.WorkflowStageState;
import com.helix.workflow.service.WorkflowEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Public surface — gateway routes {@code /workflow/**} → here via StripPrefix=1. */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowEngine engine;

    public WorkflowController(WorkflowEngine engine) {
        this.engine = engine;
    }

    public record MaterialiseRequest(String applicationReference, String jurisdiction, String segment) {
    }

    public record AdvanceRequest(String stageKey, String actorType, String note) {
    }

    public record BlockRequest(String reason) {
    }

    @PostMapping("/instances")
    public WorkflowInstance materialise(@RequestBody MaterialiseRequest body,
                                         @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null || body.applicationReference() == null || body.applicationReference().isBlank()) {
            throw ApiException.badRequest("applicationReference is required");
        }
        return engine.materialise(body.applicationReference(), body.jurisdiction(), body.segment(), actor);
    }

    @GetMapping("/instances")
    public List<WorkflowInstance> active() {
        return engine.activeInstances();
    }

    @GetMapping("/instances/{ref}")
    public Map<String, Object> view(@PathVariable("ref") String applicationReference) {
        return engine.view(applicationReference);
    }

    @PostMapping("/instances/{ref}/advance")
    public WorkflowInstance advance(@PathVariable("ref") String applicationReference,
                                     @RequestBody AdvanceRequest body,
                                     @RequestHeader(value = "X-Actor", required = false) String actor) {
        if (body == null || body.stageKey() == null) {
            throw ApiException.badRequest("stageKey is required");
        }
        return engine.advance(applicationReference, body.stageKey(),
                body.actorType(), body.note(), actor);
    }

    @PostMapping("/instances/{ref}/stages/{key}/record")
    public WorkflowInstance record(@PathVariable("ref") String applicationReference,
                                    @PathVariable("key") String stageKey,
                                    @RequestBody(required = false) AdvanceRequest body,
                                    @RequestHeader(value = "X-Actor", required = false) String actor) {
        String actorType = body == null ? null : body.actorType();
        String note = body == null ? null : body.note();
        return engine.record(applicationReference, stageKey, actorType, note, actor);
    }

    @PostMapping("/instances/{ref}/stages/{key}/block")
    public WorkflowStageState block(@PathVariable("ref") String applicationReference,
                                     @PathVariable("key") String stageKey,
                                     @RequestBody BlockRequest body,
                                     @RequestHeader(value = "X-Actor", required = false) String actor) {
        String reason = body == null ? null : body.reason();
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to block a stage");
        }
        return engine.block(applicationReference, stageKey, reason, actor);
    }

    @PostMapping("/instances/{ref}/stages/{key}/unblock")
    public WorkflowStageState unblock(@PathVariable("ref") String applicationReference,
                                       @PathVariable("key") String stageKey,
                                       @RequestHeader(value = "X-Actor", required = false) String actor) {
        return engine.unblock(applicationReference, stageKey, actor);
    }

    @GetMapping("/sla-breaches")
    public List<Map<String, Object>> slaBreaches() {
        return engine.slaBreaches();
    }

    @PostMapping("/sla-sweep")
    public Map<String, Object> sweep() {
        int n = engine.slaSweepNow();
        return Map.of("flagged", n);
    }
}
