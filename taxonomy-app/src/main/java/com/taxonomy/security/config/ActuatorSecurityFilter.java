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

/** Protects non-public Actuator endpoints with a dedicated metrics credential. */
@Component
public class ActuatorSecurityFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${taxonomy.metrics.token:}")
    private String metricsToken;

    @Value("${taxonomy.metrics.allow-unauthenticated:false}")
    private boolean allowUnauthenticated;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info")) {
            filterChain.doFilter(request, response);
            return;
        }

        if ((metricsToken == null || metricsToken.isBlank()) && allowUnauthenticated) {
            filterChain.doFilter(request, response);
            return;
        }

        if (metricsToken != null && !metricsToken.isBlank()
                && (matchesToken(request.getHeader("X-Metrics-Token"))
                || matchesBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Metrics authentication required\","
                + "\"status\":401,\"detail\":\"A valid metrics credential is required.\"}");
    }

    private boolean matchesBearerToken(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(
                        true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        return matchesToken(authorization.substring(BEARER_PREFIX.length()).trim());
    }

    private boolean matchesToken(String candidate) {
        if (candidate == null || metricsToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                metricsToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
