package com.helix.common.security;

import java.util.Set;
import java.util.TreeSet;

/**
 * Request-scoped holder for the <em>authenticated</em> identity derived from a validated
 * credential (OIDC JWT claims or an LDAP bind), populated by
 * {@link AuthenticatedActorHeaderFilter} in the secure profiles only.
 *
 * <p>This is the bridge that lets token/directory-derived roles drive the existing role
 * model: {@link com.helix.common.rbac.ActorDirectory#rolesFor(String)} consults this holder
 * first, so an authenticated actor's roles come from their verified credential rather than
 * the {@code ACTOR_ROLE} master. In the default {@code none} profile the filter never runs,
 * so {@link #current()} is always {@code null} and every consumer behaves exactly as before.</p>
 */
public final class AuthContext {

    /** The authenticated actor and the roles carried by their verified credential. */
    public record Identity(String actor, Set<String> roles) {
        public Identity {
            roles = roles == null ? Set.of() : new TreeSet<>(roles);
        }
    }

    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();

    private AuthContext() { }

    public static void set(Identity identity) { HOLDER.set(identity); }

    public static void clear() { HOLDER.remove(); }

    /** The current request's authenticated identity, or {@code null} when unauthenticated / {@code none} mode. */
    public static Identity current() { return HOLDER.get(); }
}
