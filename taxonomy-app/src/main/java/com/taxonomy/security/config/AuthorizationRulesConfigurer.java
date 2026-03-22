package com.taxonomy.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/**
 * Shared authorization rules used by both the form-login and Keycloak
 * security configurations. Ensures that role-based access control is
 * consistent regardless of the authentication method.
 */
@Component
public class AuthorizationRulesConfigurer {

    @Value("${taxonomy.security.swagger-public:true}")
    private boolean swaggerPublic;

    /**
     * Configures the shared authorization rules for all security filter chains.
     *
     * @param auth the authorization registry to configure
     */
    public void configure(
            AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        // Public resources
        auth.requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**").permitAll();

        // OIDC callback endpoints (must be public for Keycloak redirects)
        auth.requestMatchers("/login/oauth2/**", "/oauth2/**").permitAll();

        // Change-password page — must be accessible to authenticated users
        auth.requestMatchers("/change-password").authenticated();

        // Health / status endpoints
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

        // Workspace — reads for any user, writes for ADMIN
        auth.requestMatchers(HttpMethod.GET,    "/api/workspace/**").authenticated();
        auth.requestMatchers(HttpMethod.POST,   "/api/workspace/**").hasRole("ADMIN");

        auth.requestMatchers(HttpMethod.POST,   "/api/export/**").hasAnyRole("USER", "ARCHITECT", "ADMIN");

        // Reading API — any authenticated user
        auth.requestMatchers(HttpMethod.GET, "/api/**").authenticated();

        // POST to analyze/search etc. — any authenticated user
        auth.requestMatchers(HttpMethod.POST, "/api/analyze").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/justify-leaf").authenticated();

        // GUI — any authenticated user
        auth.requestMatchers("/**").authenticated();
    }
}
