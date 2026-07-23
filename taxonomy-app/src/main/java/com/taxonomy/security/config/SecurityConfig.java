package com.taxonomy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Spring Security configuration for local form-login/HTTP-Basic mode. */
@Configuration
@EnableMethodSecurity
@Profile("!keycloak")
public class SecurityConfig {

    private final AuthorizationRulesConfigurer authRules;
    private final PasswordChangeRequiredFilter passwordChangeRequiredFilter;

    public SecurityConfig(AuthorizationRulesConfigurer authRules,
                          PasswordChangeRequiredFilter passwordChangeRequiredFilter) {
        this.authRules = authRules;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher statelessApiClient = SecurityConfig::isStatelessApiClient;

        http
            .authorizeHttpRequests(auth -> authRules.configure(auth))
            .csrf(csrf -> csrf.ignoringRequestMatchers(statelessApiClient))
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.sameOrigin())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .formLogin(Customizer.withDefaults())
            .httpBasic(Customizer.withDefaults())
            .logout(Customizer.withDefaults())
            .addFilterAfter(passwordChangeRequiredFilter, BasicAuthenticationFilter.class);

        return http.build();
    }

    /**
     * A request is stateless only when it targets the API and carries an
     * explicit HTTP authentication scheme. Browser session requests retain
     * normal CSRF protection.
     */
    static boolean isStatelessApiClient(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && (authorization.regionMatches(true, 0, "Basic ", 0, 6)
                || authorization.regionMatches(true, 0, "Bearer ", 0, 7));
    }
}