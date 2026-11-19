package com.taxonomy.security.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/** Recover a rejected local login form without processing its credentials again. */
final class LocalLoginCsrfAccessDeniedHandler implements AccessDeniedHandler {
    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();
    private final AuthenticationTrustResolverImpl trust = new AuthenticationTrustResolverImpl();
    private final DefaultRedirectStrategy redirect = new DefaultRedirectStrategy();

    LocalLoginCsrfAccessDeniedHandler() {
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = trust.isAuthenticated(authentication);
        if (!(exception instanceof CsrfException)
                || !"POST".equals(request.getMethod())
                || !"/login".equals(SecurityConfig.protocolPath(request))) {
            fallback.handle(request, response, exception);
            return;
        }
        // A successful login rotates the token. A repeated old form can therefore
        // be rejected while its browser session is already authenticated.
        redirect.sendRedirect(request, response,
                authenticated ? "/" : "/login?expired");
    }
}
