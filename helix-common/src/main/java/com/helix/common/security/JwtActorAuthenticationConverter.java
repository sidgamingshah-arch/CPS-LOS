package com.helix.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives the platform actor + roles from a <b>validated</b> JWT. The username claim
 * (configurable, default {@code preferred_username}, falling back to {@code sub}) becomes
 * the authentication name — and therefore the injected {@code X-Actor}. The roles claim
 * (configurable, dotted paths + array/delimited-string tolerant) is mapped into the existing
 * role model as {@code ROLE_<ROLE>} authorities, upper-cased so token roles line up with the
 * {@code ACTOR_ROLE} vocabulary (CREDIT_OPS, TREASURY_OPS, …) consumed by SoD checks.
 */
public class JwtActorAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String principalClaim;
    private final String rolesClaim;
    private final String rolePrefix;

    public JwtActorAuthenticationConverter(String principalClaim, String rolesClaim, String rolePrefix) {
        this.principalClaim = principalClaim == null || principalClaim.isBlank() ? "sub" : principalClaim;
        this.rolesClaim = rolesClaim == null || rolesClaim.isBlank() ? "roles" : rolesClaim;
        this.rolePrefix = rolePrefix == null ? "" : rolePrefix;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String actor = asString(claimByPath(jwt.getClaims(), principalClaim));
        if (!StringUtils.hasText(actor)) {
            actor = jwt.getSubject();
        }
        Collection<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String role : extractRoles(jwt)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return new JwtAuthenticationToken(jwt, authorities, actor);
    }

    private Set<String> extractRoles(Jwt jwt) {
        Object raw = claimByPath(jwt.getClaims(), rolesClaim);
        Set<String> roles = new LinkedHashSet<>();
        collect(raw, roles);
        Set<String> normalised = new LinkedHashSet<>();
        for (String r : roles) {
            String v = r;
            if (!rolePrefix.isEmpty() && v.startsWith(rolePrefix)) {
                v = v.substring(rolePrefix.length());
            }
            v = v.trim();
            if (!v.isEmpty()) normalised.add(v.toUpperCase());
        }
        return normalised;
    }

    private static void collect(Object raw, Set<String> out) {
        if (raw == null) return;
        if (raw instanceof Collection<?> c) {
            for (Object o : c) collect(o, out);
        } else if (raw instanceof String s) {
            for (String part : s.split("[,\\s]+")) {
                if (!part.isBlank()) out.add(part);
            }
        } else {
            out.add(String.valueOf(raw));
        }
    }

    /** Navigate a possibly-dotted claim path through nested maps (e.g. {@code realm_access.roles}). */
    @SuppressWarnings("unchecked")
    private static Object claimByPath(Map<String, Object> claims, String path) {
        if (claims == null || path == null || path.isBlank()) return null;
        if (!path.contains(".")) return claims.get(path);
        Object current = claims;
        List<String> segments = new ArrayList<>(List.of(path.split("\\.")));
        for (String seg : segments) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(seg);
            if (current == null) return null;
        }
        return current;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
