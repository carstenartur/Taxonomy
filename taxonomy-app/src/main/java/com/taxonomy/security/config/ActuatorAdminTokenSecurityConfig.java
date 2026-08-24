package com.taxonomy.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Lets the dedicated {@link ActuatorSecurityFilter} authenticate non-browser
 * monitoring clients without weakening the ordinary form-login or Keycloak
 * authorization rules.
 *
 * <p>When no admin token is configured this chain matches nothing, so sensitive
 * Actuator endpoints retain the normal authenticated-user requirement. When a
 * token is configured, only non-public Actuator paths enter this permit-all
 * chain; the servlet filter still validates the actual X-Admin-Token or Bearer
 * credential and rejects missing or incorrect values.</p>
 *
 * <p>CSRF protection remains enabled. The currently exposed token-authenticated
 * Actuator endpoints are read-only; if a write-capable endpoint is exposed later,
 * it must also satisfy Spring Security's CSRF protection.</p>
 */
@Configuration
public class ActuatorAdminTokenSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain actuatorAdminTokenSecurityFilterChain(
            HttpSecurity http,
            @Value("${admin.token:}") String adminToken) throws Exception {
        RequestMatcher tokenProtectedActuator = request ->
                adminToken != null
                        && !adminToken.isBlank()
                        && ActuatorSecurityFilter.isSensitiveActuatorPath(request);

        http.securityMatcher(tokenProtectedActuator)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }
}
