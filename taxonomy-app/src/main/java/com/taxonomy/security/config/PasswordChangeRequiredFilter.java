package com.taxonomy.security.config;

import com.taxonomy.security.service.PasswordChangeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Restricts authenticated local users to password replacement until their
 * bootstrap or administrator-assigned password has been changed.
 */
@Component
@Profile("!keycloak")
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private final PasswordChangeService passwordChangeService;
    private final boolean enforcementEnabled;

    public PasswordChangeRequiredFilter(
            PasswordChangeService passwordChangeService,
            @Value("${taxonomy.security.require-password-change:false}") boolean enforcementEnabled) {
        this.passwordChangeService = passwordChangeService;
        this.enforcementEnabled = enforcementEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enforcementEnabled) {
            return true;
        }
        String path = request.getRequestURI();
        return path.equals("/login")
                || path.startsWith("/login/")
                || path.equals("/logout")
                || path.equals("/change-password")
                || path.equals("/api/account/change-password")
                || path.equals("/error")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/webjars/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())
                || !passwordChangeService.isPasswordChangeRequired(authentication.getName())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(428); // RFC 6585: Precondition Required
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"PASSWORD_CHANGE_REQUIRED\","
                    + "\"message\":\"Change the temporary password before using the API\","
                    + "\"changePasswordEndpoint\":\"/api/account/change-password\"}");
            return;
        }

        response.sendRedirect(request.getContextPath() + "/change-password");
    }
}