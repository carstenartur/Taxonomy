package com.taxonomy.shared.config;

import com.taxonomy.preferences.PreferencesService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded in-memory quota for authenticated LLM-backed API operations.
 *
 * <p>The filter is installed inside Spring Security after request authorization.
 * Each admitted local request is keyed by a digest of its authenticated username.
 * Keycloak browser and bearer requests are keyed by the same immutable
 * issuer/subject pair, never by the editable {@code preferred_username} claim.
 * Forwarding headers and peer addresses are not quota identities.</p>
 *
 * <p>Protected operations are:</p>
 * <ul>
 *   <li>{@code POST /api/analyze}</li>
 *   <li>{@code GET /api/analyze-stream}</li>
 *   <li>{@code GET /api/analyze-node}</li>
 *   <li>{@code POST /api/justify-leaf}</li>
 * </ul>
 *
 * <p>The effective limit is read at request time from {@link PreferencesService}
 * key {@code rate-limit.per-minute}, falling back to the configured property.
 * Exactly {@code 0} disables limiting. Negative values fail closed to one request
 * per minute rather than silently disabling protection.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    static final long ENTRY_RETENTION_NANOS = Duration.ofMinutes(2).toNanos();
    static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    static final int DEFAULT_MAX_TRACKED_PRINCIPALS = 10_000;

    @Value("${taxonomy.rate-limit.per-minute:10}")
    private int maxRequestsPerMinute = 10;

    /** Optional and lazy to preserve the established bootstrap dependency boundary. */
    @Autowired(required = false)
    @Lazy
    private PreferencesService preferencesService;

    private final Object stateMonitor = new Object();
    private final Map<String, WindowCounter> counters = new HashMap<>();
    private final WindowCounter overflowCounter;
    private final LongSupplier monotonicNanos;
    private final int maxTrackedPrincipals;
    private long nextCleanupAt;
    private long cleanupSweepCount;

    public RateLimitFilter() {
        this(System::nanoTime, DEFAULT_MAX_TRACKED_PRINCIPALS);
    }

    RateLimitFilter(LongSupplier monotonicNanos, int maxTrackedPrincipals) {
        this.monotonicNanos = Objects.requireNonNull(
                monotonicNanos, "monotonicNanos");
        if (maxTrackedPrincipals <= 0) {
            throw new IllegalArgumentException(
                    "maxTrackedPrincipals must be positive");
        }
        this.maxTrackedPrincipals = maxTrackedPrincipals;
        long now = monotonicNanos.getAsLong();
        overflowCounter = new WindowCounter(now);
        nextCleanupAt = now + CLEANUP_INTERVAL_NANOS;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isRateLimitedOperation(request.getMethod(), applicationPath(request))) {
            filterChain.doFilter(request, response);
            return;
        }

        int configuredLimit = preferencesService == null
                ? maxRequestsPerMinute
                : preferencesService.getInt(
                        "rate-limit.per-minute", maxRequestsPerMinute);
        if (configuredLimit == 0) {
            filterChain.doFilter(request, response);
            return;
        }
        int effectiveLimit = configuredLimit < 0 ? 1 : configuredLimit;

        String principalKey = authenticatedPrincipalKey();
        if (principalKey == null) {
            writeAuthenticationRequired(response);
            return;
        }

        long now = monotonicNanos.getAsLong();
        Acquisition acquisition = acquire(
                principalKey, effectiveLimit, now);
        if (acquisition.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        writeRateLimitResponse(
                response,
                effectiveLimit,
                acquisition.retryAfterSeconds());
    }

    /** Visible for tests and administrative test isolation. */
    public void clearCounters() {
        long now = monotonicNanos.getAsLong();
        synchronized (stateMonitor) {
            counters.clear();
            overflowCounter.reset(now);
            nextCleanupAt = now + CLEANUP_INTERVAL_NANOS;
            cleanupSweepCount = 0L;
        }
    }

    /** Visible for security-chain tests; identities themselves are never exposed. */
    int trackedPrincipalCount() {
        synchronized (stateMonitor) {
            return counters.size();
        }
    }

    /** Visible for regression tests; no principal identity or quota state is exposed. */
    long cleanupSweepCount() {
        synchronized (stateMonitor) {
            return cleanupSweepCount;
        }
    }

    private Acquisition acquire(
            String principalKey,
            int effectiveLimit,
            long now) {
        synchronized (stateMonitor) {
            cleanupExpiredEntries(now);
            WindowCounter counter = counters.get(principalKey);
            if (counter == null) {
                if (counters.size() >= maxTrackedPrincipals) {
                    counter = overflowCounter;
                } else {
                    counter = new WindowCounter(now);
                    counters.put(principalKey, counter);
                }
            }

            boolean allowed = counter.tryAcquire(effectiveLimit, now);
            return new Acquisition(
                    allowed,
                    allowed ? 0 : counter.retryAfterSeconds(now));
        }
    }

    private void cleanupExpiredEntries(long now) {
        if (now - nextCleanupAt < 0) {
            return;
        }
        counters.entrySet().removeIf(
                entry -> entry.getValue().isExpired(now));
        cleanupSweepCount++;
        nextCleanupAt = now + CLEANUP_INTERVAL_NANOS;
    }

    static String applicationPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null
                && !servletPath.isBlank()
                && !"/".equals(servletPath)) {
            return servletPath;
        }

        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null
                && !contextPath.isBlank()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    static boolean isRateLimitedOperation(String method, String path) {
        return ("POST".equalsIgnoreCase(method)
                    && ("/api/analyze".equals(path)
                        || "/api/justify-leaf".equals(path)))
                || ("GET".equalsIgnoreCase(method)
                    && ("/api/analyze-stream".equals(path)
                        || "/api/analyze-node".equals(path)));
    }

    private static String authenticatedPrincipalKey() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }

        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return oidcPrincipalKey(jwtAuthentication.getToken().getClaims());
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return oidcPrincipalKey(oidcUser.getIdToken().getClaims());
        }

        String username = authentication.getName();
        return username == null || username.isBlank()
                ? null
                : digestIdentity("local", username);
    }

    private static String oidcPrincipalKey(Map<String, Object> claims) {
        String issuer = requiredClaim(claims, "iss");
        String subject = requiredClaim(claims, "sub");
        if (issuer == null || subject == null) {
            return null;
        }
        return digestIdentity("oidc", issuer + '\u0000' + subject);
    }

    private static String requiredClaim(
            Map<String, Object> claims,
            String claimName) {
        Object value = claims.get(claimName);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static String digestIdentity(String category, String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(category.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(
                    digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    private static void writeAuthenticationRequired(
            HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"Authentication is required for this LLM operation.\","
                    + "\"status\":401}");
    }

    private static void writeRateLimitResponse(
            HttpServletResponse response,
            int effectiveLimit,
            long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(
                HttpHeaders.RETRY_AFTER,
                Long.toString(retryAfterSeconds));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"Rate limit exceeded. Maximum " + effectiveLimit
                    + " LLM requests per minute. Please wait.\","
                    + "\"status\":429}");
    }

    private record Acquisition(boolean allowed, long retryAfterSeconds) {
    }

    /** Fixed-window state; every access is serialized by {@link #stateMonitor}. */
    static final class WindowCounter {
        private int count;
        private long windowStart;
        private long lastSeen;

        WindowCounter(long now) {
            reset(now);
        }

        boolean tryAcquire(int maximum, long now) {
            rotateWindowIfNeeded(now);
            lastSeen = now;
            if (count >= maximum) {
                return false;
            }
            count++;
            return true;
        }

        long retryAfterSeconds(long now) {
            long elapsed = now - windowStart;
            long remaining = Math.max(1L, WINDOW_NANOS - elapsed);
            return Math.max(
                    1L,
                    (remaining + Duration.ofSeconds(1).toNanos() - 1L)
                            / Duration.ofSeconds(1).toNanos());
        }

        boolean isExpired(long now) {
            return now - lastSeen >= ENTRY_RETENTION_NANOS;
        }

        void reset(long now) {
            count = 0;
            windowStart = now;
            lastSeen = now;
        }

        private void rotateWindowIfNeeded(long now) {
            if (now - windowStart >= WINDOW_NANOS) {
                reset(now);
            }
        }
    }
}
