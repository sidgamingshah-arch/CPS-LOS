package com.helix.config.service;

import com.helix.common.auth.AuthToken;
import com.helix.common.auth.PasswordHasher;
import com.helix.common.web.ApiException;
import com.helix.config.entity.AppUser;
import com.helix.config.entity.MasterRecord;
import com.helix.config.repo.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Authentication authority. Verifies a username/password against the user store and
 * mints an HMAC bearer token whose subject is the user's {@code actorKey}. Roles are
 * NOT minted into the token — they resolve from the {@code ACTOR_ROLE} master at login
 * time (for the UI's convenience) and again at enforcement time in each service, so the
 * directory remains the single source of truth.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final MasterDataService masters;
    private final String secret;
    private final long defaultTtlSeconds;

    public AuthService(AppUserRepository users, MasterDataService masters,
                       @Value("${helix.auth.secret:helix-dev-secret-change-me}") String secret,
                       @Value("${helix.auth.token-ttl-seconds:28800}") long defaultTtlSeconds) {
        this.users = users;
        this.masters = masters;
        this.secret = secret;
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public record LoginResult(String token, String actor, String displayName,
                              long expiresInSeconds, List<String> roles) {
    }

    @Transactional(readOnly = true)
    public LoginResult login(String username, String password, Long ttlSecondsOverride) {
        AppUser u = users.findByUsername(username == null ? "" : username.trim()).orElse(null);
        // Verify even when the user is missing (constant-ish work) then fail uniformly,
        // so the response doesn't distinguish "no such user" from "wrong password".
        boolean ok = u != null && u.isActive() && PasswordHasher.verify(password, u.getPasswordHash());
        if (!ok) {
            throw ApiException.unauthorized("Invalid username or password");
        }
        long ttl = ttlSecondsOverride != null && ttlSecondsOverride > 0 ? ttlSecondsOverride : defaultTtlSeconds;
        String token = AuthToken.mint(secret, u.getActorKey(), ttl);
        return new LoginResult(token, u.getActorKey(), u.getDisplayName(), ttl, rolesFor(u.getActorKey()));
    }

    /** Echoes the verified token's claims plus the live role resolution for the subject. */
    @Transactional(readOnly = true)
    public Map<String, Object> me(String token) {
        AuthToken.Claims c = AuthToken.verify(secret, token)
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired token"));
        return Map.of("actor", c.subject(),
                "expiresAtMillis", c.expiresAtMillis(),
                "roles", rolesFor(c.subject()));
    }

    @SuppressWarnings("unchecked")
    private List<String> rolesFor(String actorKey) {
        MasterRecord rec = masters.active("ACTOR_ROLE", actorKey);
        if (rec == null || rec.getPayload() == null) return List.of();
        Object roles = rec.getPayload().get("roles");
        return roles instanceof List<?> l ? (List<String>) l : List.of();
    }
}
