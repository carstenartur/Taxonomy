package com.taxonomy.security.config;

import com.taxonomy.security.service.PasswordChangeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
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

    private static final String CHANGE_PASSWORD_PATH = "/change-password";
    private static final String CHANGE_PASSWORD_API_PATH =
            "/api/account/change-password";

    private final PasswordChangeService passwordChangeService;
    private final boolean enforcementEnabled;

    public PasswordChangeRequiredFilter(
            PasswordChangeService passwordChangeService,
            @Value("${taxonomy.security.require-password-change:false}")
            boolean enforcementEnabled) {
        this.passwordChangeService = passwordChangeService;
        this.enforcementEnabled = enforcementEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enforcementEnabled || isExemptPath(applicationPath(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())
                || !passwordChangeService.isPasswordChangeRequired(
                        authentication.getName())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = applicationPath(request);
        if (isApiPath(path)) {
            writePasswordChangeRequired(
                    response,
                    externalPath(request, CHANGE_PASSWORD_API_PATH));
            return;
        }

        response.sendRedirect(externalPath(request, CHANGE_PASSWORD_PATH));
    }

    /** Removes the servlet context path before matching application routes. */
    static String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null || requestUri.isBlank()) {
            return "/";
        }

        String contextPath = contextPrefix(request);
        if (contextPath.isEmpty()) {
            return requestUri;
        }
        if (requestUri.equals(contextPath)) {
            return "/";
        }
        if (requestUri.startsWith(contextPath + "/")) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    static boolean isExemptPath(String path) {
        return path.equals("/login")
                || path.startsWith("/login/")
                || path.equals("/logout")
                || path.equals(CHANGE_PASSWORD_PATH)
                || path.equals(CHANGE_PASSWORD_API_PATH)
                || path.equals("/error")
                || path.startsWith("/error/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.startsWith("/webjars/");
    }

    private static boolean isApiPath(String path) {
        return path.equals("/api") || path.startsWith("/api/");
    }

    private static String externalPath(
            HttpServletRequest request,
            String applicationPath) {
        return contextPrefix(request) + applicationPath;
    }

    private static String contextPrefix(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return contextPath == null
                || contextPath.isBlank()
                || "/".equals(contextPath)
                ? "" : contextPath;
    }

    private static void writePasswordChangeRequired(
            HttpServletResponse response,
            String changePasswordEndpoint) throws IOException {
        response.setStatus(428);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"error\":\"PASSWORD_CHANGE_REQUIRED\","
                        + "\"message\":\"Change the temporary password before using the API\","
                        + "\"changePasswordEndpoint\":\""
                        + changePasswordEndpoint + "\"}");
    }
}
