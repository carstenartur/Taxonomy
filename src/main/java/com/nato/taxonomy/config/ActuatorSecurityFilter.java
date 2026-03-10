package com.nato.taxonomy.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Protects sensitive Actuator endpoints (/actuator/metrics, /actuator/prometheus, etc.)
 * using the existing admin password mechanism.
 *
 * <ul>
 *   <li>{@code /actuator/health} and {@code /actuator/health/**} are PUBLIC (needed for Render health checks)</li>
 *   <li>{@code /actuator/info} is PUBLIC (non-sensitive)</li>
 *   <li>All other {@code /actuator/**} endpoints require the {@code X-Admin-Token} header
 *       matching the {@code ADMIN_PASSWORD} environment variable.</li>
 *   <li>If no {@code ADMIN_PASSWORD} is configured, all endpoints are accessible (backward compatible).</li>
 * </ul>
 */
@Component
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    @Value("${admin.password:}")
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

        // Check X-Admin-Token header
        String token = request.getHeader("X-Admin-Token");
        if (adminPassword.equals(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Unauthorized
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Admin authentication required for actuator endpoints\"}");
    }
}
