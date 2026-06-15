package com.helix.config.api;

import com.helix.common.web.ApiException;
import com.helix.config.service.AuthService;
import com.helix.config.service.AuthService.LoginResult;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication API. {@code /login} mints a bearer token; {@code /me} echoes the
 * verified claims; {@code /whoami} returns the X-Actor the gateway injected (the proof
 * that a verified token, not a client-supplied header, drove the identity).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password, Long ttlSeconds) {
    }

    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginRequest req) {
        return auth.login(req.username(), req.password(), req.ttlSeconds());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authz) {
        if (authz == null || !authz.startsWith("Bearer ")) {
            throw ApiException.unauthorized("Missing bearer token");
        }
        return auth.me(authz.substring("Bearer ".length()).trim());
    }

    /**
     * Returns the effective actor as seen by this service — i.e. the {@code X-Actor}
     * the gateway injected from the verified token (it strips any client-supplied one
     * when a token is present). With no token, in permissive mode, it echoes whatever
     * actor the caller passed. The single clearest spoof-prevention assertion.
     */
    @GetMapping("/whoami")
    public Map<String, Object> whoami(@RequestHeader(value = "X-Actor", required = false) String actor) {
        return Map.of("actor", actor == null ? "" : actor);
    }
}
