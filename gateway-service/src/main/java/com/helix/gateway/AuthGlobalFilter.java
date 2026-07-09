package com.helix.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's enforcement point. Every request through the gateway is inspected:
 *
 * <ul>
 *   <li>A valid {@code Authorization: Bearer} token → the verified subject is injected
 *       as {@code X-Actor} and any client-supplied {@code X-Actor} is stripped. This is
 *       what closes the "X-Actor is client-asserted" gap: downstream services keep
 *       reading {@code X-Actor}, but an authenticated caller can no longer forge it.</li>
 *   <li>An invalid / expired / tampered token → {@code 401} immediately.</li>
 *   <li>No token: in {@code PERMISSIVE} mode (default) the request passes through with
 *       the client's {@code X-Actor} intact, so the existing demo + e2e contract is
 *       unchanged; in {@code ENFORCED} mode a write (POST/PUT/PATCH/DELETE) without a
 *       token is rejected with {@code 401}.</li>
 * </ul>
 *
 * <p>Auth endpoints and actuator are always open. The gateway is the single ingress, so
 * service-to-service calls (inside the trust boundary) are unaffected.</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);
    private static final Set<HttpMethod> WRITE_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private final String secret;
    private final boolean enforced;

    public AuthGlobalFilter(@Value("${helix.auth.secret:helix-dev-secret-change-me}") String secret,
                            @Value("${helix.auth.mode:PERMISSIVE}") String mode) {
        this.secret = secret;
        this.enforced = "ENFORCED".equalsIgnoreCase(mode);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getURI().getPath();
        if (isOpen(path)) {
            return chain.filter(exchange);
        }

        String authz = req.getHeaders().getFirst("Authorization");
        if (authz != null && authz.startsWith("Bearer ")) {
            String token = authz.substring("Bearer ".length()).trim();
            Optional<GatewayAuthToken.Claims> claims = GatewayAuthToken.verify(secret, token);
            if (claims.isEmpty()) {
                return reject(exchange, "Invalid or expired token");
            }
            String subject = claims.get().subject();
            // Inject the verified actor; strip any client-supplied X-Actor first.
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.headers(h -> {
                        h.remove("X-Actor");
                        h.set("X-Actor", subject);
                    }))
                    .build();
            return chain.filter(mutated);
        }

        // No bearer token.
        if (enforced && WRITE_METHODS.contains(req.getMethod())) {
            return reject(exchange, "Authentication required: present a bearer token");
        }
        return chain.filter(exchange);
    }

    /**
     * Only login (no token yet), the gateway's own mode endpoint, and actuator bypass
     * the filter. {@code /me} and {@code /whoami} deliberately flow THROUGH it so an
     * invalid token is rejected and a valid one drives the injected {@code X-Actor}.
     */
    private boolean isOpen(String path) {
        return path.equals("/config/api/auth/login")
                || path.startsWith("/auth/")
                || path.contains("/actuator/");
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}";
        DataBuffer buf = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        log.debug("auth rejected: {} {}", exchange.getRequest().getMethod(), message);
        return exchange.getResponse().writeWith(Mono.just(buf));
    }

    @Override
    public int getOrder() {
        // Run before routing filters so header injection lands on the proxied request.
        return -100;
    }
}
