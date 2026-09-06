package com.taxonomy.security.config;

import com.taxonomy.security.keycloak.KeycloakAuthenticationEntryPoint;
import com.taxonomy.security.keycloak.KeycloakJwtAuthConverter;
import com.taxonomy.security.keycloak.KeycloakLogoutHandler;
import com.taxonomy.security.keycloak.KeycloakOidcUserService;
import com.taxonomy.security.webdav.WebDavApplicationCredentialFilter;
import com.taxonomy.shared.config.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Spring Security configuration for Keycloak/OIDC mode. */
@Configuration
@EnableMethodSecurity
@Profile("keycloak")
public class KeycloakSecurityConfig {

    private final AuthorizationRulesConfigurer authRules;
    private final KeycloakJwtAuthConverter jwtAuthConverter;
    private final KeycloakOidcUserService oidcUserService;
    private final KeycloakLogoutHandler logoutHandler;
    private final KeycloakAuthenticationEntryPoint authenticationEntryPoint;
    private final WebDavApplicationCredentialFilter webDavCredentialFilter;
    private final RateLimitFilter rateLimitFilter;

    @Autowired
    public KeycloakSecurityConfig(
            AuthorizationRulesConfigurer authRules,
            KeycloakJwtAuthConverter jwtAuthConverter,
            KeycloakOidcUserService oidcUserService,
            KeycloakLogoutHandler logoutHandler,
            KeycloakAuthenticationEntryPoint authenticationEntryPoint,
            WebDavApplicationCredentialFilter webDavCredentialFilter,
            RateLimitFilter rateLimitFilter) {
        this.authRules = authRules;
        this.jwtAuthConverter = jwtAuthConverter;
        this.oidcUserService = oidcUserService;
        this.logoutHandler = logoutHandler;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.webDavCredentialFilter = webDavCredentialFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    /** Backward-compatible constructor for focused security configuration tests. */
    public KeycloakSecurityConfig(
            AuthorizationRulesConfigurer authRules,
            KeycloakJwtAuthConverter jwtAuthConverter,
            KeycloakOidcUserService oidcUserService,
            KeycloakLogoutHandler logoutHandler,
            KeycloakAuthenticationEntryPoint authenticationEntryPoint) {
        this(authRules, jwtAuthConverter, oidcUserService, logoutHandler,
                authenticationEntryPoint, null, null);
    }

    /** Backward-compatible constructor for WebDAV security tests. */
    public KeycloakSecurityConfig(
            AuthorizationRulesConfigurer authRules,
            KeycloakJwtAuthConverter jwtAuthConverter,
            KeycloakOidcUserService oidcUserService,
            KeycloakLogoutHandler logoutHandler,
            KeycloakAuthenticationEntryPoint authenticationEntryPoint,
            WebDavApplicationCredentialFilter webDavCredentialFilter) {
        this(authRules, jwtAuthConverter, oidcUserService, logoutHandler,
                authenticationEntryPoint, webDavCredentialFilter, null);
    }

    @Bean(name = "securityFilterChain")
    SecurityFilterChain trackedSecurityFilterChain(HttpSecurity http, SessionRegistry registry) throws Exception {
        BrowserSessionConfiguration.configure(http, registry);
        return securityFilterChain(http);
    }

    /** Existing security rules, also used by focused protocol/filter configuration tests. */
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher csrfExempt = new OrRequestMatcher(
                KeycloakSecurityConfig::isStatelessBearerProtocolClient,
                KeycloakSecurityConfig::isStatelessWebDavApplicationClient,
                KeycloakSecurityConfig::isOAuth2Callback);

        http
            .authorizeHttpRequests(auth -> authRules.configure(auth))
            .csrf(csrf -> csrf.ignoringRequestMatchers(csrfExempt))
            .headers(headers -> headers
                .withObjectPostProcessor(eagerHeaderWriter())
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

        if (webDavCredentialFilter != null) {
            http.addFilterBefore(
                    webDavCredentialFilter, BearerTokenAuthenticationFilter.class);
        }
        if (rateLimitFilter != null) {
            // OIDC/Bearer authentication and request authorization must both
            // succeed before a request may consume quota.
            http.addFilterAfter(rateLimitFilter, AuthorizationFilter.class);
        }
        return http.build();
    }

    private static ObjectPostProcessor<HeaderWriterFilter> eagerHeaderWriter() {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends HeaderWriterFilter> O postProcess(O filter) {
                filter.setShouldWriteHeadersEagerly(true);
                return filter;
            }
        };
    }

    private static boolean isStatelessBearerProtocolClient(HttpServletRequest request) {
        String uri = SecurityConfig.protocolPath(request);
        if (!uri.startsWith("/api/")
                && !uri.equals("/dav/templates")
                && !uri.startsWith("/dav/templates/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static boolean isStatelessWebDavApplicationClient(
            HttpServletRequest request) {
        String uri = SecurityConfig.protocolPath(request);
        if (!uri.equals("/dav/templates") && !uri.startsWith("/dav/templates/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }

    private static boolean isOAuth2Callback(HttpServletRequest request) {
        return SecurityConfig.protocolPath(request).startsWith("/login/oauth2/code/");
    }
}
