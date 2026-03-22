package com.taxonomy.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection filter for login endpoints.
 * <p>
 * Tracks failed authentication attempts per IP address and locks out IPs that exceed
 * the configured maximum number of attempts within the lockout window.
 * <p>
 * Applies to:
 * <ul>
 *   <li>{@code POST /login} — form-based login</li>
 *   <li>{@code /api/**} — HTTP Basic authentication (tracked via 401 responses)</li>
 * </ul>
 * <p>
 * Enabled by default. Disable with {@code TAXONOMY_LOGIN_RATE_LIMIT=false}.
 * Only active in form-login mode (without Keycloak). In the Keycloak profile,
 * brute-force protection is handled by the identity provider.
 */
@Component
@Profile("!keycloak")
@ConditionalOnProperty(name = "taxonomy.security.login-rate-limit.enabled",
        havingValue = "true", matchIfMissing = true)
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimitFilter.class);

    @Value("${taxonomy.security.login-rate-limit.max-attempts:5}")
    private int maxAttempts;

    @Value("${taxonomy.security.login-rate-limit.lockout-seconds:300}")
    private int lockoutSeconds;

    private final Map<String, FailureTracker> trackers = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = getClientIp(request);
        boolean isLoginPost = "POST".equalsIgnoreCase(request.getMethod())
                && "/login".equals(request.getRequestURI());
        boolean isApiPath = request.getRequestURI().startsWith("/api/");

        // Authenticated users must not be blocked by brute-force protection —
        // they have already proven their identity via session or HTTP Basic.
        // Explicitly exclude AnonymousAuthenticationToken so that unauthenticated
        // API requests remain subject to lockout.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);

        // Check if IP is currently locked out
        FailureTracker tracker = trackers.get(clientIp);
        if (tracker != null && tracker.isLockedOut(maxAttempts, lockoutSeconds)) {
            if (isLoginPost || (isApiPath && !isAuthenticated)) {
                log.warn("LOGIN_LOCKED ip={} attempts={}", clientIp, tracker.getCount());
                response.setStatus(423); // 423 Locked
                response.setContentType("application/json");
                long remainingSeconds = tracker.getRemainingLockoutSeconds(lockoutSeconds);
                response.getWriter().write(
                        "{\"error\":\"Too many failed login attempts. Try again in "
                        + remainingSeconds + " seconds.\","
                        + "\"status\":423,"
                        + "\"retryAfterSeconds\":" + remainingSeconds + "}");
                return;
            }
        }

        // Proceed with the filter chain
        filterChain.doFilter(request, response);

        // After the request: track failures
        int status = response.getStatus();

        if (isLoginPost && status == 302) {
            // Form login: 302 redirect to /login?error means failure
            String redirectLocation = response.getHeader("Location");
            if (redirectLocation != null && redirectLocation.contains("error")) {
                recordFailure(clientIp);
            } else {
                // Successful login — clear the tracker
                trackers.remove(clientIp);
            }
        } else if (isApiPath && status == 401) {
            // HTTP Basic: 401 Unauthorized means bad credentials
            recordFailure(clientIp);
        }
    }

    private void recordFailure(String clientIp) {
        FailureTracker tracker = trackers.computeIfAbsent(clientIp, k -> new FailureTracker());
        int count = tracker.recordFailure(lockoutSeconds);
        if (count >= maxAttempts) {
            log.warn("LOGIN_RATE_LIMIT_TRIGGERED ip={} attempts={}", clientIp, count);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Visible for testing — clears all failure trackers. */
    public void clearTrackers() {
        trackers.clear();
    }

    /** Visible for testing — returns the tracker map. */
    public Map<String, FailureTracker> getTrackers() {
        return trackers;
    }

    /**
     * Tracks failed login attempts for a single IP address.
     */
    static class FailureTracker {
        private int count = 0;
        private long firstFailureTime = 0;

        synchronized int recordFailure(int lockoutSeconds) {
            long now = System.currentTimeMillis();
            long windowMs = lockoutSeconds * 1000L;

            // If the window has expired, reset the counter
            if (firstFailureTime > 0 && (now - firstFailureTime) > windowMs) {
                count = 0;
                firstFailureTime = now;
            }

            if (firstFailureTime == 0) {
                firstFailureTime = now;
            }
            return ++count;
        }

        synchronized boolean isLockedOut(int maxAttempts, int lockoutSeconds) {
            if (count < maxAttempts) {
                return false;
            }
            long elapsed = System.currentTimeMillis() - firstFailureTime;
            long windowMs = lockoutSeconds * 1000L;
            if (elapsed > windowMs) {
                // Lockout expired — reset
                count = 0;
                firstFailureTime = 0;
                return false;
            }
            return true;
        }

        synchronized long getRemainingLockoutSeconds(int lockoutSeconds) {
            long elapsed = System.currentTimeMillis() - firstFailureTime;
            long remaining = (lockoutSeconds * 1000L - elapsed) / 1000;
            return Math.max(0, remaining);
        }

        synchronized int getCount() {
            return count;
        }
    }
}
