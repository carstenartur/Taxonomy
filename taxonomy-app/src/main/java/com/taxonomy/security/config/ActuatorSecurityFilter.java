package com.taxonomy.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects the Actuator discovery root and sensitive Actuator endpoints with the
 * dedicated machine-token mechanism.
 *
 * <ul>
 *   <li>{@code /actuator/health}, {@code /actuator/health/**}, and
 *       {@code /actuator/info} are public for platform probes and non-sensitive
 *       deployment metadata.</li>
 *   <li>The exact {@code /actuator} discovery root and every other
 *       {@code /actuator/**} path are machine-token protected when an
 *       {@code ADMIN_PASSWORD} is configured.</li>
 *   <li>Monitoring clients may use either {@code X-Admin-Token} or one strict
 *       {@code Authorization: Bearer <token>} value.</li>
 *   <li>When no machine token is configured, this filter defers to the ordinary
 *       local-user or Keycloak authenticated-user security chain.</li>
 * </ul>
 *
 * <p>This filter is registered explicitly after Spring Security's delegating
 * filter proxy by {@link ActuatorAdminTokenSecurityConfig}; it is not a
 * component-scanned servlet filter.</p>
 */
public final class ActuatorSecurityFilter extends OncePerRequestFilter {

    static final int MAX_TOKEN_CANDIDATE_LENGTH = 512;
    static final String UNAUTHORIZED_CODE = "ACTUATOR_MACHINE_TOKEN_REQUIRED";
    static final String REGISTRATION_NAME = "actuatorSecurityFilterRegistration";

    private static final String LEGACY_TOKEN_HEADER = "X-Admin-Token";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_MESSAGE =
            "A valid Actuator machine token is required.";

    private final String adminToken;

    ActuatorSecurityFilter(String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = applicationPath(request);

        if (!isActuatorPath(path) || isPublicActuatorPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Without a machine token, defer to the ordinary authenticated-user rule.
        if (adminToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (matchesToken(request.getHeader(LEGACY_TOKEN_HEADER))
                || matchesBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            filterChain.doFilter(request, response);
            return;
        }

        writeUnauthorized(response);
    }

    static boolean isSensitiveActuatorPath(HttpServletRequest request) {
        String path = applicationPath(request);
        return isActuatorPath(path) && !isPublicActuatorPath(path);
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

    private static boolean isActuatorPath(String path) {
        return "/actuator".equals(path) || path.startsWith("/actuator/");
    }

    private static boolean isPublicActuatorPath(String path) {
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info");
    }

    private boolean matchesBearerToken(String authorization) {
        if (authorization == null
                || authorization.length()
                        > BEARER_PREFIX.length() + MAX_TOKEN_CANDIDATE_LENGTH
                || !authorization.regionMatches(
                        true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String candidate = authorization.substring(BEARER_PREFIX.length());
        if (candidate.isEmpty()
                || candidate.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        return matchesToken(candidate);
    }

    private boolean matchesToken(String candidate) {
        if (candidate == null
                || candidate.isEmpty()
                || candidate.length() > MAX_TOKEN_CANDIDATE_LENGTH) {
            return false;
        }
        return MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeUnauthorized(HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":401,\"code\":\""
                + UNAUTHORIZED_CODE
                + "\",\"error\":\""
                + UNAUTHORIZED_MESSAGE
                + "\"}");
    }
}
