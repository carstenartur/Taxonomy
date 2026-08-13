package com.taxonomy.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

/**
 * Shared authorization rules used by both the form-login and Keycloak
 * security configurations. Rules are ordered from most specific to least
 * specific so that state-changing endpoints are never accidentally covered by
 * a generic authenticated-user fallback.
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

        auth.requestMatchers("/api/admin/status", "/api/admin/verify").authenticated();
        auth.requestMatchers("/admin/**", "/api/admin/**", "/api/preferences/**",
                        "/api/diagnostics", "/api/prompts/**")
                .hasRole("ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/relations/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/proposals/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/proposals/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/proposals/**").hasAnyRole("ARCHITECT", "ADMIN");

        // Git-authoritative architecture decisions use identity-based endpoints
        // outside the historic /api/relations and /api/proposals paths. Keep the
        // same global role gate here; repository/workspace authority remains an
        // additional controller-level check.
        auth.requestMatchers(
                        HttpMethod.POST,
                        "/api/architecture/relations/**",
                        "/api/architecture/proposals/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(
                        HttpMethod.PUT,
                        "/api/architecture/relations/**",
                        "/api/architecture/proposals/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(
                        HttpMethod.DELETE,
                        "/api/architecture/relations/**",
                        "/api/architecture/proposals/**")
                .hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/dsl/parse", "/api/dsl/validate", "/api/dsl/format")
                .authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/dsl/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/git/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.GET, "/api/context/**").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/context/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.GET, "/api/workspace/**").authenticated();
        // Provisioning creates only the authenticated user's isolated working
        // copy. It must remain available to every product role and is ordered
        // before the privileged catch-all for workspace administration.
        auth.requestMatchers(HttpMethod.POST, "/api/workspace/provision")
                .hasAnyRole("USER", "ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST,
                        "/api/workspace/sync-from-shared",
                        "/api/workspace/publish",
                        "/api/workspace/resolve-diverged")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/workspace/**").hasRole("ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/workspace/**").hasRole("ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/workspace/**").hasRole("ADMIN");

        auth.requestMatchers(HttpMethod.GET, "/api/repositories/**").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/repositories")
                .hasRole("ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/repositories/*/workspaces")
                .hasAnyRole("USER", "ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/repositories/*/forks")
                .hasAnyRole("ARCHITECT", "ADMIN");
        // Global authentication is only the outer gate. Repository OWNER authority is
        // enforced by ArchitectureRepositoryController/RepositoryMembershipService.
        auth.requestMatchers(HttpMethod.PUT, "/api/repositories/*/members/*")
                .authenticated();
        auth.requestMatchers(HttpMethod.DELETE, "/api/repositories/*/members/*")
                .authenticated();

        auth.requestMatchers(HttpMethod.POST, "/api/import/preview/**")
                .hasAnyRole("USER", "ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/import/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/documents/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/provenance/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/provenance/**").hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/provenance/**").hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/coverage/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/coverage/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.POST, "/api/architecture/metadata/recompute")
                .hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST,
                        "/api/projects/*/analyses",
                        "/api/projects/*/requirements/*/analyses",
                        "/api/projects/*/analysis-jobs/*/retry-failed")
                .hasAnyRole("USER", "ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/projects/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PATCH, "/api/projects/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/projects/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/projects/**")
                .hasAnyRole("ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/solutions/**", "/api/products/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PATCH, "/api/solutions/**", "/api/products/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.PUT, "/api/solutions/**", "/api/products/**")
                .hasAnyRole("ARCHITECT", "ADMIN");
        auth.requestMatchers(HttpMethod.DELETE, "/api/solutions/**", "/api/products/**")
                .hasAnyRole("ARCHITECT", "ADMIN");

        // End-user calculations and file transformations use POST bodies but do
        // not persist architecture decisions. They are explicitly enumerated so
        // a newly introduced POST endpoint cannot inherit the same permission.
        auth.requestMatchers(HttpMethod.POST,
                        "/api/recommend",
                        "/api/gap/**",
                        "/api/patterns/**",
                        "/api/explain/**",
                        "/api/graph/**",
                        "/api/diagram/**",
                        "/api/scores/**",
                        "/api/export/**",
                        "/api/report/**")
                .hasAnyRole("USER", "ARCHITECT", "ADMIN");

        auth.requestMatchers(HttpMethod.POST, "/api/account/change-password").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/analyze").authenticated();
        auth.requestMatchers(HttpMethod.POST, "/api/justify-leaf").authenticated();

        auth.requestMatchers(HttpMethod.GET, "/api/**").authenticated();
        auth.requestMatchers(HttpMethod.HEAD, "/api/**").authenticated();
        auth.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll();

        auth.requestMatchers("/api/**").denyAll();
        auth.requestMatchers("/**").authenticated();
    }
}
