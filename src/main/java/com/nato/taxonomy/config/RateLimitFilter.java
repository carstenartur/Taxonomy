package com.nato.taxonomy.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory rate limiter for LLM-backed API endpoints.
 * Limits requests per IP address per minute to prevent quota exhaustion.
 *
 * <p>Protected endpoints:
 * <ul>
 *   <li>{@code POST /api/analyze}</li>
 *   <li>{@code GET /api/analyze-stream}</li>
 *   <li>{@code GET /api/analyze-node}</li>
 *   <li>{@code POST /api/justify-leaf}</li>
 * </ul>
 *
 * <p>Configure via {@code TAXONOMY_RATE_LIMIT_PER_MINUTE} (default: 10).
 * Set to 0 to disable rate limiting.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${taxonomy.rate-limit.per-minute:10}")
    private int maxRequestsPerMinute;

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                      FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Only rate-limit LLM-backed endpoints
        if (!isRateLimitedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Disabled if set to 0
        if (maxRequestsPerMinute <= 0) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        WindowCounter counter = counters.computeIfAbsent(clientIp, k -> new WindowCounter());

        if (counter.incrementAndCheck(maxRequestsPerMinute)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Rate limit exceeded. Maximum " + maxRequestsPerMinute
                    + " LLM requests per minute. Please wait.\","
                    + "\"status\":429}");
        }
    }

    private boolean isRateLimitedPath(String path) {
        return path.equals("/api/analyze")
            || path.equals("/api/analyze-stream")
            || path.equals("/api/analyze-node")
            || path.equals("/api/justify-leaf");
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Sliding window counter per minute.
     */
    static class WindowCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        boolean incrementAndCheck(int max) {
            long now = System.currentTimeMillis();
            // Reset window every 60 seconds — synchronized to prevent a race between
            // the reset check and the increment that would count against the new window
            if (now - windowStart > 60_000) {
                synchronized (this) {
                    if (now - windowStart > 60_000) {
                        count.set(1);
                        windowStart = now;
                        return true;
                    }
                }
            }
            return count.incrementAndGet() <= max;
        }
    }
}
