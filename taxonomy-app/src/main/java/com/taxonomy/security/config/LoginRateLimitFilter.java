package com.taxonomy.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * Bounded peer lockout for new local form-login and HTTP-Basic authentication attempts.
 *
 * <p>The filter is installed inside Spring Security after the security context has been
 * restored and before the form-login and HTTP-Basic authentication filters. An existing
 * authenticated session therefore bypasses peer lockout, while a new authentication attempt
 * from a locked peer is rejected before credentials are evaluated.</p>
 *
 * <p>Only authoritative authentication outcomes are counted: a failed {@code POST /login}
 * response and an HTTP {@code 401} response to an explicit Basic credential on
 * {@code /api/**}. Missing credentials, bearer credentials and unrelated authorization
 * failures do not allocate peer state.</p>
 */
@Component
@Profile("!keycloak")
@ConditionalOnProperty(name = "taxonomy.security.login-rate-limit.enabled",
        havingValue = "true", matchIfMissing = true)
public class LoginRateLimitFilter extends OncePerRequestFilter {

    static final int DEFAULT_MAX_TRACKED_PEERS = 10_000;
    private static final long MAX_CLEANUP_INTERVAL_NANOS =
            Duration.ofMinutes(1).toNanos();
    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();
    private static final Logger log =
            LoggerFactory.getLogger(LoginRateLimitFilter.class);

    private final Object stateMonitor = new Object();
    private final Map<String, FailureTracker> trackers = new HashMap<>();
    private final FailureTracker overflowTracker;
    private final LongSupplier monotonicNanos;
    private final int maxTrackedPeers;
    private final int maxAttempts;
    private final long lockoutNanos;
    private final long cleanupIntervalNanos;
    private long nextCleanupAt;

    @Autowired
    public LoginRateLimitFilter(
            @Value("${taxonomy.security.login-rate-limit.max-attempts:5}")
            int maxAttempts,
            @Value("${taxonomy.security.login-rate-limit.lockout-seconds:300}")
            int lockoutSeconds) {
        this(System::nanoTime, DEFAULT_MAX_TRACKED_PEERS,
                maxAttempts, lockoutSeconds);
    }

    LoginRateLimitFilter(
            LongSupplier monotonicNanos,
            int maxTrackedPeers,
            int maxAttempts,
            int lockoutSeconds) {
        this.monotonicNanos = Objects.requireNonNull(
                monotonicNanos, "monotonicNanos");
        if (maxTrackedPeers <= 0) {
            throw new IllegalArgumentException(
                    "maxTrackedPeers must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "taxonomy.security.login-rate-limit.max-attempts must be positive");
        }
        if (lockoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "taxonomy.security.login-rate-limit.lockout-seconds must be positive");
        }
        this.maxTrackedPeers = maxTrackedPeers;
        this.maxAttempts = maxAttempts;
        this.lockoutNanos = Duration.ofSeconds(lockoutSeconds).toNanos();
        this.cleanupIntervalNanos = Math.min(
                lockoutNanos, MAX_CLEANUP_INTERVAL_NANOS);
        long now = monotonicNanos.getAsLong();
        this.overflowTracker = new FailureTracker(now);
        this.nextCleanupAt = now + cleanupIntervalNanos;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = applicationPath(request);
        boolean formLoginAttempt = isFormLoginAttempt(request.getMethod(), path);
        boolean basicApiAttempt = isBasicApiAttempt(request, path);

        if (!formLoginAttempt && !basicApiAttempt) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String peerKey = peerKey(request);
        long now = monotonicNanos.getAsLong();
        LockState currentLock = currentLock(peerKey, now);
        if (currentLock.locked()) {
            log.warn("LOGIN_LOCKED peerDigest={} attempts={}",
                    peerKey, currentLock.failureCount());
            writeLockedResponse(response, currentLock.retryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);

        int responseStatus = response.getStatus();
        boolean errorRedirect = hasErrorRedirect(response);
        boolean failed = (formLoginAttempt && errorRedirect)
                || (basicApiAttempt
                    && responseStatus == HttpServletResponse.SC_UNAUTHORIZED);
        if (failed) {
            int count = recordFailure(peerKey, monotonicNanos.getAsLong());
            if (count >= maxAttempts) {
                log.warn("LOGIN_RATE_LIMIT_TRIGGERED peerDigest={} attempts={}",
                        peerKey, count);
            }
            return;
        }

        boolean successfulFormResponse = formLoginAttempt
                && isRedirect(responseStatus)
                && !errorRedirect;
        if (isAuthenticated() || successfulFormResponse) {
            clearFailures(peerKey);
        }
    }

    /** Removes the servlet context path before matching security routes. */
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

    static boolean isFormLoginAttempt(String method, String path) {
        return "POST".equalsIgnoreCase(method) && "/login".equals(path);
    }

    static boolean isBasicApiAttempt(HttpServletRequest request, String path) {
        if (path == null || !path.startsWith("/api/")) {
            return false;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null
                && authorization.regionMatches(true, 0, "Basic ", 0, 6);
    }

    /** Visible for test isolation without exposing peer identities or mutable state. */
    public void clearTrackers() {
        long now = monotonicNanos.getAsLong();
        synchronized (stateMonitor) {
            trackers.clear();
            overflowTracker.reset(now);
            nextCleanupAt = now + cleanupIntervalNanos;
        }
    }

    /** Visible for tests and diagnostics; peer keys remain private. */
    public int trackedPeerCount() {
        synchronized (stateMonitor) {
            return trackers.size();
        }
    }

    int overflowFailureCount() {
        synchronized (stateMonitor) {
            return overflowTracker.failureCount();
        }
    }

    private LockState currentLock(String peerKey, long now) {
        synchronized (stateMonitor) {
            cleanupExpiredEntries(now, false);
            FailureTracker tracker = trackers.get(peerKey);
            if (tracker == null && trackers.size() >= maxTrackedPeers) {
                cleanupExpiredEntries(now, true);
                if (trackers.size() >= maxTrackedPeers) {
                    tracker = overflowTracker;
                }
            }
            return tracker == null
                    ? LockState.unlocked()
                    : tracker.lockState(maxAttempts, now, lockoutNanos);
        }
    }

    private int recordFailure(String peerKey, long now) {
        synchronized (stateMonitor) {
            cleanupExpiredEntries(now, false);
            FailureTracker tracker = trackers.get(peerKey);
            if (tracker == null) {
                if (trackers.size() >= maxTrackedPeers) {
                    cleanupExpiredEntries(now, true);
                }
                if (trackers.size() >= maxTrackedPeers) {
                    tracker = overflowTracker;
                } else {
                    tracker = new FailureTracker(now);
                    trackers.put(peerKey, tracker);
                }
            }
            return tracker.recordFailure(now, lockoutNanos);
        }
    }

    private void clearFailures(String peerKey) {
        synchronized (stateMonitor) {
            trackers.remove(peerKey);
        }
    }

    private void cleanupExpiredEntries(long now, boolean force) {
        if (!force && now - nextCleanupAt < 0) {
            return;
        }
        trackers.entrySet().removeIf(
                entry -> entry.getValue().isExpired(now, lockoutNanos));
        if (overflowTracker.isExpired(now, lockoutNanos)) {
            overflowTracker.reset(now);
        }
        nextCleanupAt = now + cleanupIntervalNanos;
    }

    private static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static boolean hasErrorRedirect(HttpServletResponse response) {
        String location = response.getHeader(HttpHeaders.LOCATION);
        return isRedirect(response.getStatus())
                && location != null
                && location.contains("login?error");
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private static String peerKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return digestPeer(remoteAddress == null ? "" : remoteAddress);
    }

    private static String digestPeer(String remoteAddress) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("login-peer".getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(
                    digest.digest(remoteAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeLockedResponse(
            HttpServletResponse response,
            long retryAfterSeconds) throws IOException {
        response.setStatus(423);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER,
                Long.toString(retryAfterSeconds));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"error\":\"Too many failed login attempts.\","
                        + "\"status\":423,"
                        + "\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }

    private record LockState(
            boolean locked,
            long retryAfterSeconds,
            int failureCount) {

        private static LockState unlocked() {
            return new LockState(false, 0, 0);
        }
    }

    private static final class FailureTracker {
        private int failureCount;
        private long windowStartedAt;

        private FailureTracker(long now) {
            reset(now);
        }

        private int recordFailure(long now, long windowNanos) {
            if (isExpired(now, windowNanos)) {
                reset(now);
            }
            if (failureCount == 0) {
                windowStartedAt = now;
            }
            return ++failureCount;
        }

        private LockState lockState(
                int maximumAttempts,
                long now,
                long windowNanos) {
            if (isExpired(now, windowNanos)) {
                reset(now);
                return LockState.unlocked();
            }
            if (failureCount < maximumAttempts) {
                return LockState.unlocked();
            }
            long elapsed = now - windowStartedAt;
            long remaining = Math.max(1L, windowNanos - elapsed);
            long retryAfter = Math.max(1L,
                    (remaining + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND);
            return new LockState(true, retryAfter, failureCount);
        }

        private boolean isExpired(long now, long windowNanos) {
            return failureCount == 0 || now - windowStartedAt >= windowNanos;
        }

        private int failureCount() {
            return failureCount;
        }

        private void reset(long now) {
            failureCount = 0;
            windowStartedAt = now;
        }
    }
}
