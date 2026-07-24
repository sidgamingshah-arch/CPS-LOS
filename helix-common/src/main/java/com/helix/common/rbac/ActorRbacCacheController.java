package com.helix.common.rbac;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Per-service hook to drop the {@link ActorDirectory} snapshot so an approved
 * ACTOR_ROLE change takes effect immediately instead of waiting out the cache TTL.
 * Mirrors the AI-governance cache endpoint.
 */
@RestController
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class ActorRbacCacheController {

    private final ActorDirectory directory;
    private final String serviceName;

    public ActorRbacCacheController(ActorDirectory directory,
                                    @Value("${spring.application.name:unknown}") String serviceName) {
        this.directory = directory;
        this.serviceName = serviceName;
    }

    @PostMapping("/api/governance/rbac/cache/invalidate")
    public Map<String, Object> invalidate() {
        directory.invalidate();
        return Map.of("invalidated", true, "service", serviceName);
    }

    /** The effective governance posture on this service (fail-open vs fail-closed) — G7. */
    @GetMapping("/api/governance/rbac/posture")
    public Map<String, Object> posture() {
        return directory.postureStatus();
    }

    /**
     * The effective action → allowed-roles catalogue on this service: each {@link ProtectedAction}
     * with the roles permitted to perform it and whether that came from the admin-editable
     * {@code ACTION_ROLE} master or the compile-time enum fallback. This is the read side of
     * "RBAC configuration at admin level".
     */
    @GetMapping("/api/governance/rbac/actions")
    public Map<String, Object> actions() {
        return directory.catalogue();
    }
}
