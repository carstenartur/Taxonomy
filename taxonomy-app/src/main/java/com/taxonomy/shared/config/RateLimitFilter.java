package com.taxonomy.shared.config;

import com.taxonomy.preferences.PreferencesService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded in-memory rate limiter for LLM-backed API endpoints.
 *
 * <p>Authenticated requests are keyed by a fixed-length digest of the principal
 * established by Spring Security. Requests without a principal fall back to a
 * digest of the direct servlet peer; caller-controlled forwarding headers are
 * deliberately ignored. Expired entries are evicted and the map has a hard
 * cardinality bound.</p>
 *
 * <p>Protected operations are POST {@code /api/analyze}, GET
 * {@code /api/analyze-stream}, GET {@code /api/analyze-node}, and POST
 * {@code /api/justify-leaf}. OPTIONS, HEAD, and wrong-method requests do not
 * consume an LLM budget.</p>
 *
 * <p>The effective limit is read at request time from {@link PreferencesService}
 * key {@code rate-limit.per-minute}, falling back to the configured property.
 * Exactly zero disables limiting. Negative values fail closed to one request per
 * minute instead of silently disabling protection.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private static final long ENTRY_RETENTION_NANOS = Duration.ofMinutes(2).toNanos();
    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    @Value("${taxonomy.rate-limit.per-minute:10}")
    private int maxRequestsPerMinute = 10;

    /** Optional — injected lazily to avoid circular dependencies. */
    @Autowired(required = false)
    @Lazy
    private PreferencesService preferencesService;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final WindowCounter overflowCounter;
    private final Object admissionMonitor = new Object();
    private final AtomicLong nextCleanupAt = new AtomicLong();
    private final LongSupplier monotonicNanos;

    public RateLimitFilter() {
        this(System::nanoTime);
    }

    RateLimitFilter(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos);
        long now = monotonicNanos.getAsLong();
        overflowCounter = new WindowCounter(now);
        nextCleanupAt.set(now + CLEANUP_INTERVAL_NANOS);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = applicationPath(request);
        if (!isRateLimitedOperation(request.getMethod(), path)) {
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

        long now = monotonicNanos.getAsLong();
        WindowCounter counter = counterFor(clientKey(request), now);
        if (counter.tryAcquire(effectiveLimit, now)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeRateLimitResponse(
                response, effectiveLimit, counter.retryAfterSeconds(now));
    }

    /** Visible for testing — clears all per-client and overflow counters. */
    public void clearCounters() {
        long now = monotonicNanos.getAsLong();
        synchronized (admissionMonitor) {
            counters.clear();
            overflowCounter.reset(now);
            nextCleanupAt.set(now + CLEANUP_INTERVAL_NANOS);
        }
    }

    int trackedClientCount() {
        return counters.size();
    }

    private WindowCounter counterFor(String clientKey, long now) {
        cleanupExpiredEntries(now, false);
        WindowCounter existing = counters.get(clientKey);
        if (existing != null) {
            return existing;
        }

        synchronized (admissionMonitor) {
            existing = counters.get(clientKey);
            if (existing != null) {
                return existing;
            }
            if (counters.size() >= MAX_TRACKED_CLIENTS) {
                cleanupExpiredEntries(now, true);
            }
            if (counters.size() >= MAX_TRACKED_CLIENTS) {
                return overflowCounter;
            }
            WindowCounter created = new WindowCounter(now);
            counters.put(clientKey, created);
            return created;
        }
    }

    private void cleanupExpiredEntries(long now, boolean force) {
        if (!force && now - nextCleanupAt.get() < 0) {
            return;
        }
        synchronized (admissionMonitor) {
            if (!force && now - nextCleanupAt.get() < 0) {
                return;
            }
            counters.entrySet().removeIf(
                    entry -> entry.getValue().isExpired(now));
            nextCleanupAt.set(now + CLEANUP_INTERVAL_NANOS);
        }
    }

    private static String applicationPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }

        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }

    private static boolean isRateLimitedOperation(String method, String path) {
        return ("POST".equalsIgnoreCase(method)
                    && ("/api/analyze".equals(path)
                        || "/api/justify-leaf".equals(path)))
                || ("GET".equalsIgnoreCase(method)
                    && ("/api/analyze-stream".equals(path)
                        || "/api/analyze-node".equals(path)));
    }

    private static String clientKey(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null
                && !principal.getName().isBlank()) {
            return digestIdentity("principal", principal.getName());
        }
        String peer = request.getRemoteAddr();
        return digestIdentity(
                "peer", peer == null || peer.isBlank() ? "unknown" : peer);
    }

    private static String digestIdentity(String category, String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(category.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] value = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            return category + ':' + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeRateLimitResponse(
            HttpServletResponse response,
            int effectiveLimit,
            long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader("Cache-Control", "no-store");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Rate limit exceeded. Maximum " + effectiveLimit
                    + " LLM requests per minute. Please wait.\","
                    + "\"status\":429}");
    }

    /** Fixed-window counter with explicit last-use tracking for bounded eviction. */
    static final class WindowCounter {
        private int count;
        private long windowStart;
        private long lastSeen;

        WindowCounter(long now) {
            reset(now);
        }

        synchronized boolean tryAcquire(int maximum, long now) {
            rotateWindowIfNeeded(now);
            lastSeen = now;
            if (count >= maximum) {
                return false;
            }
            count++;
            return true;
        }

        synchronized long retryAfterSeconds(long now) {
            rotateWindowIfNeeded(now);
            long elapsed = now - windowStart;
            long remaining = Math.max(1L, WINDOW_NANOS - elapsed);
            return Math.max(1L, (remaining + 999_999_999L) / 1_000_000_000L);
        }

        synchronized boolean isExpired(long now) {
            return now - lastSeen >= ENTRY_RETENTION_NANOS;
        }

        synchronized void reset(long now) {
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
