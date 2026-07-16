package com.helix.common.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Injects library-level DEFAULTS for the SSO layer at lowest precedence, so any explicit
 * service configuration still wins.
 *
 * <p>The SSO deps ({@code spring-ldap-core}) pull Spring Boot's LDAP auto-configuration onto the
 * classpath, which — even in the default {@code none}/{@code oidc} profiles where LDAP is never
 * used — registers an actuator LDAP health indicator that probes {@code localhost:389} and drives
 * {@code /actuator/health} to DOWN. That would break the health-gated startup and is a regression.
 * We disable that health indicator by default (a deployment can re-enable it explicitly). This is
 * the ONLY behavioural default the security layer adds, and it is additive + overridable.</p>
 */
public class SecurityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "helixSecurityDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return;
        }
        Map<String, Object> defaults = new HashMap<>();
        // Keep /actuator/health green regardless of an (unused) auto-configured LDAP client.
        defaults.put("management.health.ldap.enabled", false);
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }
}
