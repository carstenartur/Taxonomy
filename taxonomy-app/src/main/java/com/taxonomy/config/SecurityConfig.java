package com.taxonomy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Central Spring Security configuration.
 * <p>
 * Protects GUI (form login + session) and REST API (same session from browser).
 * CSRF protection is kept enabled for browser-based sessions.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // public resources
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()

                // health / status endpoints
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()

                // OpenAPI / Swagger UI (development)
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // Admin-only
                .requestMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")

                // Write operations on architecture endpoints — ARCHITECT or ADMIN
                .requestMatchers(HttpMethod.POST,   "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN")
                .requestMatchers(HttpMethod.DELETE,  "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN")

                .requestMatchers(HttpMethod.POST,   "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN")
                .requestMatchers(HttpMethod.DELETE,  "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN")

                .requestMatchers(HttpMethod.POST,   "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN")
                .requestMatchers(HttpMethod.DELETE,  "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN")

                .requestMatchers(HttpMethod.POST,   "/api/export/**").hasAnyRole("USER", "ARCHITECT", "ADMIN")

                // Reading API — any authenticated user
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()

                // POST to analyze/search etc. — any authenticated user
                .requestMatchers(HttpMethod.POST, "/api/analyze").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/justify-leaf").authenticated()

                // GUI — any authenticated user
                .requestMatchers("/**").authenticated()
            )
            .formLogin(Customizer.withDefaults())
            .logout(Customizer.withDefaults());

        // CSRF protection is enabled by default (important for browser sessions).
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
