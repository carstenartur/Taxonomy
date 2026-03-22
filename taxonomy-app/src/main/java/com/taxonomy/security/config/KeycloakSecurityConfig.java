package com.taxonomy.security.config;

import com.taxonomy.security.keycloak.KeycloakAuthenticationEntryPoint;
import com.taxonomy.security.keycloak.KeycloakJwtAuthConverter;
import com.taxonomy.security.keycloak.KeycloakOidcUserService;
import com.taxonomy.security.keycloak.KeycloakLogoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Spring Security configuration for Keycloak/OIDC mode.
 * <p>
 * Active when the {@code keycloak} profile is enabled.
 * Uses OAuth2 Login (browser SSO) and OAuth2 Resource Server (JWT for REST API).
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
        http
            .authorizeHttpRequests(auth -> authRules.configure(auth))
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**")
                .ignoringRequestMatchers("/login/oauth2/code/**")
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
            // Browser: OAuth2 Login → Keycloak login page
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo
                    .oidcUserService(oidcUserService)
                )
            )
            // REST API: JWT Bearer Token
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(authenticationEntryPoint)
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthConverter)
                )
            )
            // Logout: also log out at Keycloak (RP-Initiated Logout)
            .logout(logout -> logout
                .logoutSuccessHandler(logoutHandler)
            );

        return http.build();
    }
}
