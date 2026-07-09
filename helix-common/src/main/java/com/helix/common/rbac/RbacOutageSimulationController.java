package com.helix.common.rbac;

import com.helix.common.web.ApiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TEST-ONLY affordance to exercise the fail-closed governance posture (G7): forces the
 * {@link ActorDirectory} into the genuine cold-start-outage branch ({@code records()==null})
 * without a real network failure, so an e2e can prove BOTH postures deterministically while
 * config-service stays up (the posture master itself remains readable).
 *
 * <p>The whole bean is gated by {@code helix.rbac.simulate-outage-enabled=true} and does NOT
 * exist in a production run (the property defaults false). {@link ActorDirectory} is injected
 * via {@link ObjectProvider} so the bean is also harmless in a service that has no directory
 * (e.g. config-service, which does not call itself) — the endpoint 404s there. It never
 * affects normal resolution: the flag is a single volatile boolean checked only at the head
 * of {@code records()}.</p>
 */
@RestController
@ConditionalOnProperty(name = "helix.rbac.simulate-outage-enabled", havingValue = "true")
public class RbacOutageSimulationController {

    private final ObjectProvider<ActorDirectory> directory;
    private final String serviceName;

    public RbacOutageSimulationController(ObjectProvider<ActorDirectory> directory,
                                          @Value("${spring.application.name:unknown}") String serviceName) {
        this.directory = directory;
        this.serviceName = serviceName;
    }

    public record SimulateRequest(boolean enabled) {
    }

    @PostMapping("/api/governance/rbac/_simulate-outage")
    public Map<String, Object> simulate(@RequestBody SimulateRequest req) {
        ActorDirectory dir = directory.getIfAvailable();
        if (dir == null) {
            throw ApiException.notFound("No ActorDirectory on this service — nothing to simulate");
        }
        dir.setSimulateOutage(req.enabled());
        return Map.of("service", serviceName, "simulateOutage", dir.isSimulateOutage());
    }
}
