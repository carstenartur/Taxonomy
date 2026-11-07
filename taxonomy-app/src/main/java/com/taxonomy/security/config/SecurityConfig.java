package com.taxonomy.security.config;

import com.taxonomy.security.webdav.WebDavApplicationCredentialFilter;
import com.taxonomy.shared.config.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.ui.DefaultLogoutPageGeneratingFilter;
import org.springframework.security.web.authentication.ui.DefaultResourcesFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Optional;
import java.util.Map;

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
                webDavCredentialFilter, rateLimitFilter, Optional.empty());
    }

    @Bean(name = "securityFilterChain")
    SecurityFilterChain trackedSecurityFilterChain(HttpSecurity http, SessionRegistry registry) throws Exception {
        BrowserSessionConfiguration.configure(http, registry);
        return securityFilterChain(http);
    }

    /** Existing security rules, also used by focused protocol/filter configuration tests. */
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher statelessProtocolClient = SecurityConfig::isStatelessProtocolClient;
        var apiEntryPoint = new BasicAuthenticationEntryPoint();
        apiEntryPoint.setRealmName("Taxonomy");

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
            .formLogin(form -> form.loginPage("/login").permitAll())
            .httpBasic(Customizer.withDefaults())
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                    apiEntryPoint, SecurityConfig::isRestClient))
            .logout(Customizer.withDefaults());

        // A custom login page disables Spring's generated logout page and its CSS too.
        // Restore those UI filters while the standard CSRF-protected LogoutFilter performs logout.
        var logoutPage = new DefaultLogoutPageGeneratingFilter();
        logoutPage.setResolveHiddenInputs(request -> {
            var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            return token == null ? Map.of() : Map.of(token.getParameterName(), token.getToken());
        });
        http.addFilter(logoutPage);
        http.addFilter(DefaultResourcesFilter.css());

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

    /**
     * CSRF runs after context restoration and before Basic authentication. A
     * restored browser authentication therefore still needs CSRF, even with a
     * Basic header: that filter may reuse the same-name authenticated principal.
     * Local mode has no Bearer authentication filter.
     */
    static boolean isStatelessProtocolClient(HttpServletRequest request) {
        // Decide from the restored authority before inspecting untrusted routing
        // or credential hints. No request value may skip this authentication check.
        var restored = SecurityContextHolder.getContext().getAuthentication();
        if (restored != null && restored.isAuthenticated()
                && !(restored instanceof AnonymousAuthenticationToken)) {
            return false;
        }
        String uri = protocolPath(request);
        if (!uri.startsWith("/api/")
                && !uri.equals("/dav/templates")
                && !uri.startsWith("/dav/templates/")) {
            return false;
        }
        String authorization = request.getHeader("Authorization");
        return authorization != null
                && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }

    static String protocolPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context == null || context.isBlank() || !uri.startsWith(context)
                ? uri : uri.substring(context.length());
    }

    static boolean isRestClient(HttpServletRequest request) {
        String path = protocolPath(request);
        return path.startsWith("/api/") || path.startsWith("/oslc/");
    }
}
