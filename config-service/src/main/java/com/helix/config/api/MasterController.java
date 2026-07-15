package com.helix.config.api;

import com.helix.config.entity.MasterRecord;
import com.helix.config.service.MasterDataService;
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

/**
 * Generic master-maintenance API (front-end CRUD + maker-checker + bulk).
 * Every "X Master" in the platform is a {@code masterType} over this controller.
 */
@RestController
@RequestMapping("/api/masters")
public class MasterController {

    private final MasterDataService masters;

    public MasterController(MasterDataService masters) {
        this.masters = masters;
    }

    @GetMapping("/{type}")
    public List<MasterRecord> listActive(@PathVariable String type) {
        return masters.listActive(type);
    }

    @GetMapping("/{type}/{key}")
    public MasterRecord active(@PathVariable String type, @PathVariable String key,
                               @RequestParam(required = false) String jurisdiction) {
        // Jurisdiction-aware resolution when supplied (override → default → fallback);
        // otherwise the jurisdiction-agnostic default.
        return (jurisdiction == null || jurisdiction.isBlank())
                ? masters.active(type, key)
                : masters.resolve(type, key, jurisdiction);
    }

    @GetMapping("/{type}/{key}/history")
    public List<MasterRecord> history(@PathVariable String type, @PathVariable String key) {
        return masters.history(type, key);
    }

    @GetMapping("/queue/pending")
    public List<MasterRecord> pending() {
        return masters.pendingQueue();
    }

    /** Maker submits a record for approval. */
    @PostMapping("/{type}")
    public MasterRecord submit(@PathVariable String type, @RequestBody Map<String, Object> body,
                               @RequestHeader(value = "X-Actor", defaultValue = "master.maker") String actor) {
        String key = String.valueOf(body.get("recordKey"));
        String jur = body.get("jurisdiction") == null ? null : String.valueOf(body.get("jurisdiction"));
        // Optional maker rationale ("comment"/"rationale") for the change — persisted &
        // audited, never merged into the payload.
        Object rat = body.get("comment") != null ? body.get("comment") : body.get("rationale");
        String comment = rat == null ? null : String.valueOf(rat);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        return masters.submit(type, key, jur, payload, actor, comment);
    }

    @PostMapping("/{type}/bulk")
    public List<MasterRecord> bulk(@PathVariable String type, @RequestBody List<Map<String, Object>> rows,
                                   @RequestHeader(value = "X-Actor", defaultValue = "master.maker") String actor) {
        return masters.bulkSubmit(type, rows, actor);
    }

    @PostMapping("/records/{id}/approve")
    public MasterRecord approve(@PathVariable Long id, @RequestParam(required = false) String comment,
                                @RequestHeader(value = "X-Actor", defaultValue = "master.checker") String actor) {
        return masters.approve(id, actor, comment);
    }

    @PostMapping("/records/{id}/reject")
    public MasterRecord reject(@PathVariable Long id, @RequestParam(required = false) String comment,
                               @RequestHeader(value = "X-Actor", defaultValue = "master.checker") String actor) {
        return masters.reject(id, actor, comment);
    }
}
