package com.helix.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bridges Spring Security's authenticated principal into the platform's existing
 * {@code X-Actor} identity model. Registered <b>only</b> in the {@code oidc} / {@code ldap}
 * profiles (never in {@code none}), so the default behaviour is entirely unchanged.
 *
 * <p>When the request carries a validated credential (a JWT the resource server accepted, or
 * an LDAP-bound Basic credential), this filter:</p>
 * <ol>
 *   <li>Rewrites the {@code X-Actor} header on the request to the authenticated username —
 *       so downstream {@code @RequestHeader("X-Actor")} controllers transparently see the
 *       verified identity and a client-supplied {@code X-Actor} can no longer be forged.</li>
 *   <li>Publishes the identity + roles into {@link AuthContext} so
 *       {@link com.helix.common.rbac.ActorDirectory} / SoD checks resolve roles from the
 *       verified credential.</li>
 * </ol>
 *
 * <p>Unauthenticated requests (anonymous) are passed through untouched — the client's
 * {@code X-Actor}, if any, stands. In the secure profiles the filter chain rejects those on
 * protected paths with 401 before the controller runs anyway.</p>
 */
public class AuthenticatedActorHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || auth.getName() == null || auth.getName().isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String actor = auth.getName();
        Set<String> roles = new TreeSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String r = ga.getAuthority();
            if (r == null) continue;
            roles.add(r.startsWith("ROLE_") ? r.substring("ROLE_".length()) : r);
        }

        AuthContext.set(new AuthContext.Identity(actor, roles));
        try {
            chain.doFilter(new ActorHeaderRequestWrapper(request, actor), response);
        } finally {
            AuthContext.clear();
        }
    }

    /** Forces {@code X-Actor} to the authenticated username, overriding any client-supplied value. */
    private static final class ActorHeaderRequestWrapper extends HttpServletRequestWrapper {
        private static final String HEADER = "X-Actor";
        private final String actor;

        ActorHeaderRequestWrapper(HttpServletRequest request, String actor) {
            super(request);
            this.actor = actor;
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? actor : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(actor));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original != null && original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            names.add(HEADER);
            return Collections.enumeration(names);
        }
    }
}
