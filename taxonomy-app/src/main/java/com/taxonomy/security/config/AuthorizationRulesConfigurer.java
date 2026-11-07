package com.taxonomy.security.config;

import com.taxonomy.security.access.ApiAccessPolicy;
import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.service.PrincipalIdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Shared page rules and the single REST policy used by local and OIDC authentication. */
@Component
public class AuthorizationRulesConfigurer {
    @Value("${taxonomy.security.swagger-public:true}")
    private boolean swaggerPublic;
    @Autowired(required = false)
    private KeycloakPrincipalMode principalMode = KeycloakPrincipalMode.LEGACY;

    // MVC slices without identity persistence retain their existing composition seam.
    // The production identity configuration always supplies this registry service.
    @Autowired(required = false)
    private PrincipalIdentityService identities;

    private static final AuthenticationTrustResolverImpl TRUST = new AuthenticationTrustResolverImpl();

    public void configure(
            AuthorizeHttpRequestsConfigurer<org.springframework.security.config.annotation.web.builders.HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        auth.requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/webjars/**",
                        "/login/oauth2/**", "/oauth2/**")
                .permitAll();
        auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
        if (swaggerPublic) {
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
        } else {
            auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").access(this::authenticated);
        }
        auth.requestMatchers("/api/**", "/oslc/**").access((authentication, context) -> {
            var request = context.getRequest();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            var rule = ApiAccessPolicy.resolve(request.getMethod(), path);
            if (rule.access() == ApiAccessPolicy.Access.PUBLIC) return new AuthorizationDecision(true);
            var current = authentication.get();
            return new AuthorizationDecision(ApiAccessPolicy.authorize(current, rule)
                    && currentIdentity(current, request));
        });
        auth.requestMatchers("/admin/**").access((authentication, context) -> {
            var current = authentication.get();
            return new AuthorizationDecision(TRUST.isAuthenticated(current)
                    && current.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))
                    && currentIdentity(current, context.getRequest()));
        });
        auth.requestMatchers(HttpMethod.GET, "/backup-jobs").access(this::authenticated);
        auth.requestMatchers("/**").access(this::authenticated);
    }

    private AuthorizationDecision authenticated(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        var current = authentication.get();
        return new AuthorizationDecision(TRUST.isAuthenticated(current) && currentIdentity(current, context.getRequest()));
    }

    private boolean currentIdentity(Authentication authentication, HttpServletRequest request) {
        if (identities == null) return true;
        if (authentication instanceof StablePrincipal || authentication.getPrincipal() instanceof StablePrincipal) {
            try {
                identities.requireCurrent(authentication);
                return true;
            } catch (AccessDeniedException | DataAccessException | IllegalArgumentException unavailable) {
                // Neither credentials nor database exception details belong in an HTTP denial or log.
                return false;
            }
        }
        if (authentication instanceof JwtAuthenticationToken
                || (authentication instanceof OAuth2AuthenticationToken oauth2 && oauth2.getPrincipal() instanceof OidcUser)) {
            // Existing external ownership cannot change identity mode without verified migration.
            return principalMode == KeycloakPrincipalMode.LEGACY;
        }
        var session = request.getSession(false);
        if (session != null && session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                instanceof SecurityContext retained && retained.getAuthentication() == authentication) {
            // A server-side save for this request is separate from a session read.
            // Restoring a retained-only context does not populate the request repository.
            return request.getAttribute(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME)
                    instanceof SecurityContext fresh && fresh.getAuthentication() == authentication;
        }
        // Fresh stateless adapters (notably WebDAV application credentials) retain their own
        // current credential checks. They cannot make an unknown retained session authoritative.
        return true;
    }
}
