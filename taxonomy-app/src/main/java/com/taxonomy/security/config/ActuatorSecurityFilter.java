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
 * using the existing admin password mechanism.
 *
 * <ul>
 *   <li>{@code /actuator/health} and {@code /actuator/health/**} are PUBLIC (needed for platform probes)</li>
 *   <li>{@code /actuator/info} is PUBLIC (non-sensitive)</li>
 *   <li>All other {@code /actuator/**} endpoints require either the legacy
 *       {@code X-Admin-Token} header or an {@code Authorization: Bearer ...}
 *       header matching the {@code ADMIN_PASSWORD} environment variable.</li>
 *   <li>If no {@code ADMIN_PASSWORD} is configured, all endpoints are accessible (backward compatible).</li>
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
        String path = request.getRequestURI();

        // Only filter actuator paths
        if (!path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Public endpoints: health and info
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info")) {
            filterChain.doFilter(request, response);
            return;
        }

        // If no admin password configured, allow all
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
