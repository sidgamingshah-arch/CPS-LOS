package com.helix.common.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Security bootstrap + identity diagnostics, auto-exposed on every service via helix-common.
 *
 * <ul>
 *   <li>{@code GET /api/security/mode} — <b>unauthenticated</b> in every profile (an OPEN path in
 *       the secure chains). The SPA reads it before login to decide between the mock/actor-selector
 *       login ({@code none}) and the Authorization-Code + PKCE SSO redirect ({@code oidc}).</li>
 *   <li>{@code GET /api/security/whoami} — the effective identity as this service sees it: the
 *       {@code X-Actor} (which the secure profiles have overridden with the verified username) plus
 *       the roles derived from the credential. In a secure profile this path requires a valid
 *       credential, so it returns 401 without one — the crisp proof of enforcement.</li>
 * </ul>
 *
 * <p>Deliberately mapped under {@code /api/security/*} (not {@code /api/auth/*}) so it never
 * collides with config-service's existing {@code AuthController}.</p>
 */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final HelixSecurityProperties props;

    public SecurityController(HelixSecurityProperties props) {
        this.props = props;
    }

    @GetMapping("/mode")
    public Map<String, Object> mode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", props.getMode());
        out.put("oidc", props.isOidc());
        out.put("ldap", props.isLdap());
        out.put("secured", props.isSecured());
        if (props.isOidc()) {
            HelixSecurityProperties.Spa spa = props.getSpa();
            Map<String, Object> oidc = new LinkedHashMap<>();
            oidc.put("authorizationUri", spa.getAuthorizationUri());
            oidc.put("tokenUri", spa.getTokenUri());
            oidc.put("clientId", spa.getClientId());
            oidc.put("scopes", spa.getScopes());
            oidc.put("redirectUri", spa.getRedirectUri());
            out.put("oidcClient", oidc);
        }
        return out;
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami(@RequestHeader(value = "X-Actor", required = false) String actor) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);

        TreeSet<String> roles = new TreeSet<>();
        AuthContext.Identity id = AuthContext.current();
        if (id != null && id.roles() != null) {
            roles.addAll(id.roles());
        } else if (authenticated) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String r = ga.getAuthority();
                if (r == null) continue;
                roles.add(r.startsWith("ROLE_") ? r.substring("ROLE_".length()) : r);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actor", actor == null ? "" : actor);
        out.put("roles", roles);
        out.put("authenticated", authenticated);
        out.put("mode", props.getMode());
        return out;
    }
}
