package com.taxonomy.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/**
 * Shared authorization rules used by both the form-login and Keycloak
 * security configurations. Rules are ordered from most specific to least
 * specific so that state-changing endpoints are never accidentally covered by
 * the generic authenticated-user fallback.
 */
@Component
public class AuthorizationRulesConfigurer {

    @Value("${taxonomy.security.swagger-public:true}")
    private boolean swaggerPublic;

    public void configure(
            AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        auth.requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**")
                .permitAll();
        auth.requestMatchers("/login/oauth2/**", "/oauth2/**").permitAll();
        auth.requestMatchers("/change-password").authenticated();
        auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();

        if (swaggerPublic) {
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
        } else {
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").authenticated();
        }

        // The UI uses these endpoints to decide whether to show the admin lock.
        // They must be reachable by authenticated non-admin users, but never grant
        // additional privileges; the controller derives the result from ROLE_ADMIN.
        auth.requestMatchers("/api/admin/status", "/api/admin/verify").authenticated();

        // Administrative surfaces. These checks are also repeated inside the
        // controller as defense in depth for diagnostics and prompt mutation.
        auth.requestMatchers("/admin/**", "/api/admin/**", "/api/preferences/**",
                        "/api/diagnostics", "/api/prompts/**")
                .hasRole("ADMIN");

        // Architecture mutation — ARCHITECT or ADMIN.
        auth.requestMatchers(HttpMethod.POST,   "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT,    "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");

        // Proposal generation and review mutate proposal state and can create or
        // delete real relations. Keep these endpoints out of the generic
        // authenticated-user fallback.
        auth.requestMatchers(HttpMethod.POST,   "/api/proposals/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT,    "/api/proposals/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/proposals/**").hasAnyRole("ARCHITECT", "ADMIN");

        // Parse, validate and format are pure in-memory transformations and are
        // safe for every authenticated reader. Materialization, commit and branch
        // operations remain architecture mutations covered by the broader rules.
        auth.requestMatchers(HttpMethod.POST, "/api/dsl/parse", "/api/dsl/validate", "/api/dsl/format")
                .authenticated();
        auth.requestMatchers(HttpMethod.POST,   "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT,    "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST,   "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT,    "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.GET,  "/api/context/**").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/context/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.GET,  "/api/workspace/**").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/workspace/**").hasRole("ADMIN");

        // Import preview is read-only, while materialization and provenance
        // registration mutate workspace state.
        auth.requestMatchers(HttpMethod.POST, "/api/import/preview/**").hasAnyRole("USER", "ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/import/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/documents/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/provenance/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/provenance/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/provenance/**").hasAnyRole("ARCHITECT", "ADMIN");

        // End-user analysis and export operations.
        auth.requestMatchers(HttpMethod.POST, "/api/export/**").hasAnyRole("USER", "ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/report/**").hasAnyRole("USER", "ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/analyze").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/justify-leaf").authenticated();

        // Reading API — any authenticated user.
        auth.requestMatchers(HttpMethod.GET, "/api/**").authenticated();

        // Remaining API requests must still be authenticated. Specific write
        // capabilities should be added above rather than relying on this rule.
        auth.requestMatchers("/api/**").authenticated();
        auth.requestMatchers("/**").authenticated();
    }
}
