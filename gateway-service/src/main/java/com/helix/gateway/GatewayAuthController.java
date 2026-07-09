package com.helix.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Surfaces the gateway's enforcement posture so clients (and the e2e) can tell whether
 * the stack is running PERMISSIVE or ENFORCED. Served locally by the gateway — no
 * route predicate matches {@code /auth/mode}, so the global filter leaves it open.
 */
@RestController
public class GatewayAuthController {

    private final String mode;

    public GatewayAuthController(@Value("${helix.auth.mode:PERMISSIVE}") String mode) {
        this.mode = mode == null ? "PERMISSIVE" : mode.toUpperCase();
    }

    @GetMapping("/auth/mode")
    public Map<String, Object> mode() {
        return Map.of("mode", mode, "enforced", "ENFORCED".equals(mode));
    }
}
