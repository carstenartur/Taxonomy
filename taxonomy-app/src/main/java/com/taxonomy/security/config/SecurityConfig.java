package com.taxonomy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security configuration for form-login mode (default, without Keycloak).
 *
 * <p>The GUI uses form-login sessions and therefore keeps CSRF protection for
 * state-changing API calls. Programmatic API clients authenticated with an
 * explicit Basic or Bearer Authorization header are treated as stateless and
 * may call the REST API without a CSRF token.</p>
 */
@Configuration
@EnableMethodSecurity
@Profile("!keycloak")
public class SecurityConfig {

    private final AuthorizationRulesConfigurer authRules;

    public SecurityConfig(AuthorizationRulesConfigurer authRules) {
        this.authRules = authRules;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher statelessApiClient = SecurityConfig::isStatelessApiClient;

        http
            .authorizeHttpRequests(auth -> authRules.configure(auth))
            .csrf(csrf -> csrf
                .ignoringRequestMatchers(statelessApiClient)
            )
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
            .logout(Customizer.withDefaults());

        return http.build();
    }

    /**
     * Explicit Authorization headers identify non-browser REST clients. Requests
     * carrying the form-login session cookie remain CSRF protected, including
     * all fetch() calls issued by the web UI.
     */
    private static boolean isStatelessApiClient(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            return false;
        }
        return authorization.regionMatches(true, 0, "Basic ", 0, 6)
                || authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
