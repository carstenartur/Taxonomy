package com.taxonomy.security.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** Keeps credentials available to the provider without treating them as an unverified identity. */
final class CredentialSafeBearerTokenAuthenticationConverter implements AuthenticationConverter {
    private final AuthenticationConverter delegate;

    private CredentialSafeBearerTokenAuthenticationConverter(AuthenticationConverter delegate) {
        this.delegate = delegate;
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
        return new CredentialSafeBearerTokenAuthenticationConverter(delegate);
    }

    @Override public Authentication convert(HttpServletRequest request) {
        var authentication = delegate.convert(request);
        if (!(authentication instanceof BearerTokenAuthenticationToken token)) {
            return authentication;
        }
        var safe = new UnverifiedBearerToken(token.getToken());
        safe.setDetails(token.getDetails());
        safe.setAuthenticated(token.isAuthenticated());
        return safe;
    }

    private static final class UnverifiedBearerToken extends BearerTokenAuthenticationToken {
        private static final long serialVersionUID = 1L;

        UnverifiedBearerToken(String token) { super(token); }
        @Override public Object getPrincipal() { return "[unverified-bearer]"; }
        @Override public String getName() { return "[unverified-bearer]"; }
    }
}
