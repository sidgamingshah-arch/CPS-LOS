package com.helix.common.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Per-service hook to drop the {@link AiGovernanceClient} snapshot so an approved
 * AI_GOVERNANCE toggle takes effect immediately instead of waiting out the cache
 * TTL. Present automatically on every service that wires the client (same
 * {@code helix.config-service.base-url} condition); read-only services without the
 * client don't expose it.
 *
 * <p>Invalidation is deliberately cheap and idempotent — admin tooling (and the
 * governance e2e) may call it after every toggle approval.</p>
 */
@RestController
@ConditionalOnProperty(name = "helix.config-service.base-url")
public class AiGovernanceCacheController {

    private final AiGovernanceClient client;
    private final String serviceName;

    public AiGovernanceCacheController(AiGovernanceClient client,
                                       @Value("${spring.application.name:unknown}") String serviceName) {
        this.client = client;
        this.serviceName = serviceName;
    }

    @PostMapping("/api/governance/ai/cache/invalidate")
    public Map<String, Object> invalidate() {
        client.invalidate();
        return Map.of("invalidated", true, "service", serviceName);
    }
}
