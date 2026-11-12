package com.taxonomy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** Keeps credentials available to the provider without treating them as an unverified identity. */
final class CredentialSafeBearerTokenAuthenticationConverter implements AuthenticationConverter {
    private static final String PREFIX = "Bearer ";
    private static final String LIMIT = "taxonomy.security.bearer.max-token-length";
    private final AuthenticationConverter delegate;
    private final int maxTokenLength;

    private CredentialSafeBearerTokenAuthenticationConverter(AuthenticationConverter delegate, int maxTokenLength) {
        if (maxTokenLength < 1) throw new IllegalArgumentException(LIMIT + " must be positive");
        this.delegate = delegate;
        this.maxTokenLength = maxTokenLength;
    }

    static AuthenticationConverter forContext(ApplicationContext context) {
        // Preserve the resource-server's converter/resolver bean precedence and default parser.
        AuthenticationConverter delegate = context.getBeanProvider(AuthenticationConverter.class).getIfAvailable();
        if (delegate == null) {
            var standard = new BearerTokenAuthenticationConverter();
            var resolver = context.getBeanProvider(BearerTokenResolver.class).getIfAvailable();
            if (resolver != null) standard.setBearerTokenResolver(resolver);
            delegate = standard;
        }
        return new CredentialSafeBearerTokenAuthenticationConverter(delegate, context.getEnvironment().getProperty(LIMIT, Integer.class, 8192));
    }

    @Override public Authentication convert(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean declared = declaresBearer(header);
        if (declared && header.length() - PREFIX.length() > maxTokenLength) throw unavailable();
        var authentication = delegate.convert(request);
        if (declared && authentication == null) {
            // The CSRF matcher admits this explicit protocol. Require its provider
            // to receive a credential even when a custom resolver is installed.
            throw unavailable();
        }
        if (!(authentication instanceof BearerTokenAuthenticationToken token)) {
            return authentication;
        }
        if (token.getToken().length() > maxTokenLength) throw unavailable();
        var safe = new UnverifiedBearerToken(token.getToken());
        safe.setDetails(token.getDetails());
        safe.setAuthenticated(token.isAuthenticated());
        return safe;
    }

    static boolean declaresBearer(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    private static OAuth2AuthenticationException unavailable() {
        return new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Bearer credential is unavailable"));
    }

    private static final class UnverifiedBearerToken extends BearerTokenAuthenticationToken {
        private static final long serialVersionUID = 1L;

        UnverifiedBearerToken(String token) { super(token); }
        @Override public Object getPrincipal() { return "[unverified-bearer]"; }
        @Override public String getName() { return "[unverified-bearer]"; }
    }
}
