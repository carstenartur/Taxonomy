package com.taxonomy.security.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
 * token is configured, the exact {@code /actuator} discovery root and all
 * non-public {@code /actuator/**} paths enter this permit-all chain; the servlet
 * filter remains the authoritative token validator.</p>
 *
 * <p>The servlet filter has one explicit registration immediately after Spring
 * Security's delegating filter proxy. That makes authorization select the
 * correct chain first and prevents component scanning from registering the
 * machine-token filter at an implicit or duplicate order.</p>
 *
 * <p>CSRF protection remains enabled. The currently exposed token-authenticated
 * Actuator endpoints are read-only; if a write-capable endpoint is exposed later,
 * it must also satisfy Spring Security's CSRF protection.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ActuatorAdminTokenSecurityConfig {

    static final int ACTUATOR_FILTER_ORDER =
            SecurityProperties.DEFAULT_FILTER_ORDER + 1;

    @Bean
    ActuatorSecurityFilter actuatorSecurityFilter(
            @Value("${admin.token:}") String adminToken) {
        return new ActuatorSecurityFilter(adminToken);
    }

    @Bean(name = ActuatorSecurityFilter.REGISTRATION_NAME)
    FilterRegistrationBean<ActuatorSecurityFilter>
            actuatorSecurityFilterRegistration(ActuatorSecurityFilter filter) {
        FilterRegistrationBean<ActuatorSecurityFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName(ActuatorSecurityFilter.REGISTRATION_NAME);
        registration.addUrlPatterns("/*");
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(ACTUATOR_FILTER_ORDER);
        return registration;
    }

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
