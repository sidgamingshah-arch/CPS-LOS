package com.helix.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.util.StringUtils;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Profile-gated real authentication for every servlet service (inherited via helix-common's
 * {@code com.helix} component scan). The active {@link SecurityFilterChain} is selected by
 * {@code helix.security.mode}; exactly one of the three beans below matches.
 *
 * <h3>{@code none} (DEFAULT — non-negotiable regression contract)</h3>
 * A permit-all, header-stripped, CSRF-disabled chain that reproduces the platform's original
 * behaviour <em>exactly</em>: no authentication, the {@code X-Actor} header remains a claimable
 * identity, and {@code ActorDirectory} role resolution is untouched. Providing this chain is
 * also what stops Spring Boot's default auto-configuration from securing every endpoint the
 * moment the security starter lands on the classpath.
 *
 * <h3>{@code oidc}</h3>
 * An OAuth2 resource server validating JWT bearer tokens (JWKS / issuer discovery / a symmetric
 * HS256 secret for self-contained deployments). The validated token's username + roles claims
 * drive the identity via {@link JwtActorAuthenticationConverter} +
 * {@link AuthenticatedActorHeaderFilter}, winning over any client {@code X-Actor}. Protected
 * paths return 401 without a valid token.
 *
 * <h3>{@code ldap}</h3>
 * HTTP-Basic credentials bound against an LDAP directory; group membership resolves to roles.
 * Same actor+roles model, same {@code X-Actor} override.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(HelixSecurityProperties.class)
public class HelixSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(HelixSecurityConfig.class);

    /**
     * Always-open paths in the secure profiles: health, the SPA bootstrap, legacy login, and the
     * external customer/vendor self-service portal ({@code /api/portal/**}) — the latter is reached
     * by unauthenticated external parties whose ONLY credential is the one-time RFI token, so it
     * must not require a bearer/basic identity even under {@code oidc}/{@code ldap}. The portal is
     * itself token-gated end to end (see {@code PortalService}); allow-listing it here only defers
     * the credential check to that token, it does not open any thread data to anonymous callers.
     */
    private static final String[] OPEN_PATHS = {"/actuator/**", "/api/security/mode", "/api/auth/login",
            "/api/portal/**"};

    // ---- none (default) -------------------------------------------------------------------------

    /**
     * DEFAULT. Permit-all — behaviourally identical to having no Spring Security at all. Headers
     * are disabled so responses are byte-identical to the pre-security stack (no added
     * X-Frame-Options / Cache-Control), and CSRF is off so the existing write endpoints keep
     * working without a token. The full 1996-assertion / 54-suite regression runs under this bean.
     */
    @Bean
    @ConditionalOnProperty(name = "helix.security.mode", havingValue = "none", matchIfMissing = true)
    SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .headers(headers -> headers.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // ---- oidc -----------------------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "helix.security.mode", havingValue = "oidc")
    SecurityFilterChain oidcFilterChain(HttpSecurity http, HelixSecurityProperties props) throws Exception {
        log.info("helix.security.mode=oidc — OAuth2 JWT resource server active; identity derives from the token");
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                        .decoder(jwtDecoder(props))
                        .jwtAuthenticationConverter(new JwtActorAuthenticationConverter(
                                props.getPrincipalClaim(), props.getRolesClaim(), props.getRolePrefix()))))
                .addFilterAfter(new AuthenticatedActorHeaderFilter(), AuthorizationFilter.class);
        return http.build();
    }

    private JwtDecoder jwtDecoder(HelixSecurityProperties props) {
        if (StringUtils.hasText(props.getJwkSetUri())) {
            return NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();
        }
        if (StringUtils.hasText(props.getIssuerUri())) {
            return JwtDecoders.fromIssuerLocation(props.getIssuerUri());
        }
        if (StringUtils.hasText(props.getJwtSecret())) {
            SecretKeySpec key = new SecretKeySpec(
                    props.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        }
        throw new IllegalStateException(
                "helix.security.mode=oidc requires one of helix.security.jwk-set-uri, "
                        + "helix.security.issuer-uri, or helix.security.jwt-secret");
    }

    // ---- ldap -----------------------------------------------------------------------------------

    @Bean
    @ConditionalOnProperty(name = "helix.security.mode", havingValue = "ldap")
    SecurityFilterChain ldapFilterChain(HttpSecurity http, AuthenticationProvider ldapAuthenticationProvider)
            throws Exception {
        log.info("helix.security.mode=ldap — LDAP HTTP-Basic authentication active");
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .authenticationProvider(ldapAuthenticationProvider)
                .addFilterAfter(new AuthenticatedActorHeaderFilter(), AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "helix.security.mode", havingValue = "ldap")
    AuthenticationProvider ldapAuthenticationProvider(HelixSecurityProperties props) {
        HelixSecurityProperties.Ldap cfg = props.getLdap();
        DefaultSpringSecurityContextSource contextSource =
                new DefaultSpringSecurityContextSource(cfg.getUrl() + "/" + safe(cfg.getBaseDn()));
        if (StringUtils.hasText(cfg.getManagerDn())) {
            contextSource.setUserDn(cfg.getManagerDn());
            contextSource.setPassword(cfg.getManagerPassword() == null ? "" : cfg.getManagerPassword());
        }
        contextSource.afterPropertiesSet();

        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        if (cfg.getUserDnPatterns() != null && !cfg.getUserDnPatterns().isEmpty()) {
            authenticator.setUserDnPatterns(cfg.getUserDnPatterns().toArray(new String[0]));
        }
        if (StringUtils.hasText(cfg.getUserSearchFilter())) {
            authenticator.setUserSearch(new FilterBasedLdapUserSearch(
                    safe(cfg.getUserSearchBase()), cfg.getUserSearchFilter(), contextSource));
        }
        authenticator.afterPropertiesSet();

        DefaultLdapAuthoritiesPopulator populator =
                new DefaultLdapAuthoritiesPopulator(contextSource, safe(cfg.getGroupSearchBase()));
        populator.setGroupSearchFilter(cfg.getGroupSearchFilter());
        populator.setGroupRoleAttribute(cfg.getGroupRoleAttribute());
        populator.setConvertToUpperCase(true);

        return new LdapAuthenticationProvider(authenticator, populator);
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }
}
