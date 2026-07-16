package com.helix.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.List;

/**
 * Configuration for the profile-gated SSO layer, all under {@code helix.security.*}.
 *
 * <p><b>The single most important property is {@link #mode}.</b> Default {@code none}
 * reproduces the platform's original behaviour exactly — a permit-all filter chain that
 * leaves the {@code X-Actor}-header identity model and {@code ActorDirectory} role
 * resolution untouched. {@code oidc} turns each service into an OAuth2 <em>resource
 * server</em> validating JWT bearer tokens; {@code ldap} authenticates HTTP-Basic
 * credentials against an LDAP directory. In both secure modes the authenticated identity
 * (username + roles) is derived from the validated credential and WINS over any
 * client-supplied {@code X-Actor} header.</p>
 */
@ConfigurationProperties(prefix = "helix.security")
public class HelixSecurityProperties {

    /** {@code none} (default, permit-all) | {@code oidc} (JWT resource server) | {@code ldap}. */
    private String mode = "none";

    // ---- OIDC / JWT resource-server validation --------------------------------------------------

    /** JWKS endpoint the resource server fetches signing keys from (asymmetric, e.g. a real IdP). */
    private String jwkSetUri;

    /** OIDC issuer location for discovery-based validation ({@code /.well-known/openid-configuration}). */
    private String issuerUri;

    /**
     * Symmetric HS256 signing secret (>= 32 chars). Provided for self-contained / test deployments
     * that mint tokens locally without a full IdP; production should use {@link #jwkSetUri} or
     * {@link #issuerUri}. Exactly one of the three must be set when {@code mode=oidc}.
     */
    private String jwtSecret;

    /** Claim carrying the username/actor. Dotted paths are supported. Falls back to {@code sub}. */
    private String principalClaim = "preferred_username";

    /**
     * Claim carrying the roles/groups. Dotted paths supported (e.g. {@code realm_access.roles}).
     * The value may be a JSON array or a space/comma-delimited string.
     */
    private String rolesClaim = "roles";

    /** Optional prefix stripped from each role (e.g. {@code ROLE_}) before mapping into the model. */
    private String rolePrefix = "";

    // ---- SPA (public OIDC client) config surfaced to the frontend -------------------------------

    @NestedConfigurationProperty
    private Spa spa = new Spa();

    // ---- LDAP -----------------------------------------------------------------------------------

    @NestedConfigurationProperty
    private Ldap ldap = new Ldap();

    /** Non-secret OIDC client parameters the SPA needs to run Authorization-Code + PKCE. */
    public static class Spa {
        /** IdP authorization endpoint the SPA redirects the browser to. */
        private String authorizationUri;
        /** IdP token endpoint the SPA exchanges the code at (PKCE, no client secret). */
        private String tokenUri;
        /** Public client id registered with the IdP. */
        private String clientId;
        /** OAuth scopes requested. */
        private String scopes = "openid profile";
        /** Redirect URI registered with the IdP (defaults to the SPA origin when blank). */
        private String redirectUri;

        public String getAuthorizationUri() { return authorizationUri; }
        public void setAuthorizationUri(String v) { this.authorizationUri = v; }
        public String getTokenUri() { return tokenUri; }
        public void setTokenUri(String v) { this.tokenUri = v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { this.clientId = v; }
        public String getScopes() { return scopes; }
        public void setScopes(String v) { this.scopes = v; }
        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String v) { this.redirectUri = v; }
    }

    public static class Ldap {
        /** LDAP server URL, e.g. {@code ldap://localhost:389}. */
        private String url;
        /** Base DN, e.g. {@code dc=helix,dc=com}. */
        private String baseDn = "";
        /** Manager bind DN for search-then-bind (optional; anonymous bind if blank). */
        private String managerDn;
        private String managerPassword;
        /** Direct user DN pattern, e.g. {@code uid={0},ou=people}. Mutually exclusive with search. */
        private List<String> userDnPatterns;
        /** Search base for locating the user entry (search-then-bind). */
        private String userSearchBase = "";
        /** Search filter, e.g. {@code (uid={0})}. */
        private String userSearchFilter;
        /** Group search base for role resolution. */
        private String groupSearchBase = "";
        /** Group search filter, e.g. {@code (member={0})}. */
        private String groupSearchFilter = "(member={0})";
        /** LDAP attribute providing the role name (mapped into the role model). */
        private String groupRoleAttribute = "cn";

        public String getUrl() { return url; }
        public void setUrl(String v) { this.url = v; }
        public String getBaseDn() { return baseDn; }
        public void setBaseDn(String v) { this.baseDn = v; }
        public String getManagerDn() { return managerDn; }
        public void setManagerDn(String v) { this.managerDn = v; }
        public String getManagerPassword() { return managerPassword; }
        public void setManagerPassword(String v) { this.managerPassword = v; }
        public List<String> getUserDnPatterns() { return userDnPatterns; }
        public void setUserDnPatterns(List<String> v) { this.userDnPatterns = v; }
        public String getUserSearchBase() { return userSearchBase; }
        public void setUserSearchBase(String v) { this.userSearchBase = v; }
        public String getUserSearchFilter() { return userSearchFilter; }
        public void setUserSearchFilter(String v) { this.userSearchFilter = v; }
        public String getGroupSearchBase() { return groupSearchBase; }
        public void setGroupSearchBase(String v) { this.groupSearchBase = v; }
        public String getGroupSearchFilter() { return groupSearchFilter; }
        public void setGroupSearchFilter(String v) { this.groupSearchFilter = v; }
        public String getGroupRoleAttribute() { return groupRoleAttribute; }
        public void setGroupRoleAttribute(String v) { this.groupRoleAttribute = v; }
    }

    public String getMode() { return mode == null ? "none" : mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isOidc() { return "oidc".equalsIgnoreCase(getMode()); }
    public boolean isLdap() { return "ldap".equalsIgnoreCase(getMode()); }
    public boolean isSecured() { return isOidc() || isLdap(); }

    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String v) { this.jwkSetUri = v; }
    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String v) { this.issuerUri = v; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String v) { this.jwtSecret = v; }
    public String getPrincipalClaim() { return principalClaim; }
    public void setPrincipalClaim(String v) { this.principalClaim = v; }
    public String getRolesClaim() { return rolesClaim; }
    public void setRolesClaim(String v) { this.rolesClaim = v; }
    public String getRolePrefix() { return rolePrefix; }
    public void setRolePrefix(String v) { this.rolePrefix = v; }
    public Spa getSpa() { return spa; }
    public void setSpa(Spa spa) { this.spa = spa; }
    public Ldap getLdap() { return ldap; }
    public void setLdap(Ldap ldap) { this.ldap = ldap; }
}
