package com.taxonomy.security.config;

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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Optional;

/** Spring Security configuration for form-login mode. */
@Configuration
@EnableMethodSecurity
@Profile("!keycloak")
public class SecurityConfig {

    private final AuthorizationRulesConfigurer authRules;
    private final PasswordChangeRequiredFilter passwordChangeRequiredFilter;
    private final WebDavApplicationCredentialFilter webDavCredentialFilter;
    private final RateLimitFilter rateLimitFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;

    @Autowired
    public SecurityConfig(
            AuthorizationRulesConfigurer authRules,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
            WebDavApplicationCredentialFilter webDavCredentialFilter,
            RateLimitFilter rateLimitFilter,
            Optional<LoginRateLimitFilter> loginRateLimitFilter) {
        this.authRules = authRules;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
        this.webDavCredentialFilter = webDavCredentialFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.loginRateLimitFilter = loginRateLimitFilter.orElse(null);
    }

    /** Backward-compatible constructor for focused configuration tests. */
    SecurityConfig(
            AuthorizationRulesConfigurer authRules,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter) {
        this(authRules, passwordChangeRequiredFilter, null, null, Optional.empty());
    }

    /** Backward-compatible constructor for tests of the WebDAV filter boundary. */
    SecurityConfig(
            AuthorizationRulesConfigurer authRules,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
            WebDavApplicationCredentialFilter webDavCredentialFilter) {
        this(authRules, passwordChangeRequiredFilter,
                webDavCredentialFilter, null, Optional.empty());
    }

    /** Backward-compatible constructor for focused LLM-quota tests. */
    SecurityConfig(
            AuthorizationRulesConfigurer authRules,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
            WebDavApplicationCredentialFilter webDavCredentialFilter,
            RateLimitFilter rateLimitFilter) {
        this(authRules, passwordChangeRequiredFilter,
                webDavCredentialFilter, null, Optional.empty());
    }

    @Bean(name = "securityFilterChain")
    SecurityFilterChain trackedSecurityFilterChain(HttpSecurity http, SessionRegistry registry) throws Exception {
        BrowserSessionConfiguration.configure(http, registry);
        return securityFilterChain(http);
    }

    /** Existing security rules, also used by focused protocol/filter configuration tests. */
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher statelessProtocolClient = SecurityConfig::isStatelessProtocolClient;

        http
            .authorizeHttpRequests(auth -> authRules.configure(auth))
            .csrf(csrf -> csrf.ignoringRequestMatchers(statelessProtocolClient))
            .headers(headers -> headers
                .withObjectPostProcessor(eagerHeaderWriter())
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.sameOrigin())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .formLogin(Customizer.withDefaults())
            .httpBasic(Customizer.withDefaults())
            .logout(Customizer.withDefaults());

        if (webDavCredentialFilter != null) {
            http.addFilterBefore(webDavCredentialFilter, BasicAuthenticationFilter.class);
        }
        if (loginRateLimitFilter != null) {
            // Restore a trusted session first, then wrap the authoritative form-login
            // and HTTP-Basic authentication filters exactly once.
            http.addFilterAfter(loginRateLimitFilter, SecurityContextHolderFilter.class);
        }
        http.addFilterAfter(passwordChangeRequiredFilter, BasicAuthenticationFilter.class);
        if (rateLimitFilter != null) {
            // Authorization must succeed before a request may consume quota or
            // allocate per-principal state.
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

    /** Explicit Basic/Bearer protocol calls are stateless; browser sessions retain CSRF. */
    static boolean isStatelessProtocolClient(HttpServletRequest request) {
        String uri = protocolPath(request);
        if (!uri.startsWith("/api/")
                && !uri.equals("/dav/templates")
                && !uri.startsWith("/dav/templates/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && (authorization.regionMatches(true, 0, "Basic ", 0, 6)
                || authorization.regionMatches(true, 0, "Bearer ", 0, 7));
    }

    static String protocolPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context == null || context.isBlank() || !uri.startsWith(context)
                ? uri : uri.substring(context.length());
    }
}
