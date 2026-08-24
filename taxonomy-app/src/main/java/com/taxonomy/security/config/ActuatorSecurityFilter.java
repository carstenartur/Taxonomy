package com.taxonomy.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects sensitive Actuator endpoints (/actuator/metrics, /actuator/prometheus, etc.)
 * with the dedicated admin-token mechanism.
 *
 * <ul>
 *   <li>{@code /actuator/health} and {@code /actuator/health/**} are PUBLIC (needed for platform probes)</li>
 *   <li>{@code /actuator/info} is PUBLIC (non-sensitive)</li>
 *   <li>All other {@code /actuator/**} endpoints accept either the legacy
 *       {@code X-Admin-Token} header or an Authorization header in the form
 *       {@code Authorization: Bearer ADMIN_PASSWORD_VALUE}, where the Bearer
 *       value matches the {@code ADMIN_PASSWORD} environment variable.</li>
 *   <li>When no {@code ADMIN_PASSWORD} is configured, this filter adds no token
 *       requirement; the ordinary Spring Security authenticated-user rule remains
 *       authoritative for sensitive Actuator endpoints.</li>
 * </ul>
 */
@Component
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${admin.token:}")
    private String adminPassword;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = applicationPath(request);

        if (!path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isPublicActuatorPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Without a machine token, defer to the ordinary authenticated-user rule.
        if (adminPassword == null || adminPassword.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (matchesToken(request.getHeader("X-Admin-Token"))
                || matchesBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Admin authentication required for actuator endpoints\"}");
    }

    static boolean isSensitiveActuatorPath(HttpServletRequest request) {
        String path = applicationPath(request);
        return path.startsWith("/actuator/") && !isPublicActuatorPath(path);
    }

    static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null
                && !contextPath.isBlank()
                && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private static boolean isPublicActuatorPath(String path) {
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info");
    }

    private boolean matchesBearerToken(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        return matchesToken(authorization.substring(BEARER_PREFIX.length()).trim());
    }

    private boolean matchesToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                adminPassword.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
