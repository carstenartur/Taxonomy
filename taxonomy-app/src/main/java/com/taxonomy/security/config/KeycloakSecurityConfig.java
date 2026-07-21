package com.taxonomy.security.config;

import com.taxonomy.security.keycloak.KeycloakAuthenticationEntryPoint;
import com.taxonomy.security.keycloak.KeycloakJwtAuthConverter;
import com.taxonomy.security.keycloak.KeycloakLogoutHandler;
import com.taxonomy.security.keycloak.KeycloakOidcUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Spring Security configuration for Keycloak/OIDC mode.
 * Browser OIDC sessions retain CSRF protection; explicit JWT Bearer API clients
 * are stateless and do not require a CSRF token.
 */
@Configuration
@EnableMethodSecurity
@Profile("keycloak")
public class KeycloakSecurityConfig {

    private final AuthorizationRulesConfigurer authRules;
    private final KeycloakJwtAuthConverter jwtAuthConverter;
    private final KeycloakOidcUserService oidcUserService;
    private final KeycloakLogoutHandler logoutHandler;
    private final KeycloakAuthenticationEntryPoint authenticationEntryPoint;

    public KeycloakSecurityConfig(AuthorizationRulesConfigurer authRules,
                                  KeycloakJwtAuthConverter jwtAuthConverter,
                                  KeycloakOidcUserService oidcUserService,
                                  KeycloakLogoutHandler logoutHandler,
                                  KeycloakAuthenticationEntryPoint authenticationEntryPoint) {
        this.authRules = authRules;
        this.jwtAuthConverter = jwtAuthConverter;
        this.oidcUserService = oidcUserService;
        this.logoutHandler = logoutHandler;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher csrfExempt = new OrRequestMatcher(
                KeycloakSecurityConfig::isStatelessBearerApiClient,
                new AntPathRequestMatcher("/login/oauth2/code/**"));

        http
            .authorizeHttpRequests(auth -> authRules.configure(auth))
            .csrf(csrf -> csrf.ignoringRequestMatchers(csrfExempt))
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.sameOrigin())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)))
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(authenticationEntryPoint)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)))
            .logout(logout -> logout.logoutSuccessHandler(logoutHandler));

        return http.build();
    }

    private static boolean isStatelessBearerApiClient(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }
}
