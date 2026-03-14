package com.taxonomy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Central Spring Security configuration.
 * <p>
 * Protects GUI (form login + session) and REST API (HTTP Basic for programmatic clients).
 * CSRF protection is enabled for browser sessions but disabled for {@code /api/**} paths
 * so that stateless REST clients authenticated via HTTP Basic can POST without a CSRF token.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${taxonomy.security.swagger-public:true}")
    private boolean swaggerPublic;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> {
                // public resources
                auth.requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll();

                // change-password page — must be accessible to authenticated users
                auth.requestMatchers("/change-password").authenticated();

                // health / status endpoints
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();

                // OpenAPI / Swagger UI — configurable
                if (swaggerPublic) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                } else {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated();
                }

                // Admin-only
                auth.requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN");
                auth.requestMatchers("/api/preferences/**").hasRole("ADMIN");

                // Write operations on architecture endpoints — ARCHITECT or ADMIN
                auth.requestMatchers(HttpMethod.POST,   "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");
                auth.requestMatchers(HttpMethod.PUT,    "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");
                auth.requestMatchers(HttpMethod.DELETE,  "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");

                auth.requestMatchers(HttpMethod.POST,   "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");
                auth.requestMatchers(HttpMethod.PUT,    "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");
                auth.requestMatchers(HttpMethod.DELETE,  "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");

                auth.requestMatchers(HttpMethod.POST,   "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");
                auth.requestMatchers(HttpMethod.PUT,    "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");
                auth.requestMatchers(HttpMethod.DELETE,  "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");

                // Context navigation — reads for any user, writes for ARCHITECT/ADMIN
                auth.requestMatchers(HttpMethod.GET,    "/api/context/**").authenticated();
                auth.requestMatchers(HttpMethod.POST,   "/api/context/**").hasAnyRole("ARCHITECT", "ADMIN");

                auth.requestMatchers(HttpMethod.POST,   "/api/export/**").hasAnyRole("USER", "ARCHITECT", "ADMIN");

                // Reading API — any authenticated user
                auth.requestMatchers(HttpMethod.GET, "/api/**").authenticated();

                // POST to analyze/search etc. — any authenticated user
                auth.requestMatchers(HttpMethod.POST, "/api/analyze").authenticated();
                auth.requestMatchers(HttpMethod.POST, "/api/justify-leaf").authenticated();

                // GUI — any authenticated user
                auth.requestMatchers("/**").authenticated();
            })
            .csrf(csrf -> csrf
                // Disable CSRF for REST API paths — stateless HTTP Basic clients cannot
                // participate in CSRF token exchange.  Browser-based sessions that call
                // /api/** via fetch() still include the CSRF token from the meta tags,
                // but it is simply ignored for these paths.
                .ignoringRequestMatchers("/api/**")
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

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
