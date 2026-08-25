package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    @Test
    void contextPathAndSpoofedForwardingHeadersCannotResetPrincipalBudget()
            throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        RateLimitFilter filter = filter(now, 1);

        MockHttpServletResponse first = perform(
                filter, "POST", "/taxonomy", "/api/analyze",
                "alice", "198.51.100.1", "203.0.113.1");
        MockHttpServletResponse second = perform(
                filter, "POST", "/taxonomy", "/api/analyze",
                "alice", "198.51.100.1", "203.0.113.99");
        MockHttpServletResponse otherUser = perform(
                filter, "POST", "/taxonomy", "/api/analyze",
                "bob", "198.51.100.1", "203.0.113.99");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isEqualTo("60");
        assertThat(second.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(second.getContentAsString()).contains("\"status\":429");
        assertThat(otherUser.getStatus()).isEqualTo(200);
    }

    @Test
    void unauthenticatedFallbackUsesDirectPeerNotForwardingHeader()
            throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        RateLimitFilter filter = filter(now, 1);

        MockHttpServletResponse first = perform(
                filter, "GET", "", "/api/analyze-node",
                null, "192.0.2.10", "203.0.113.1");
        MockHttpServletResponse second = perform(
                filter, "GET", "", "/api/analyze-node",
                null, "192.0.2.10", "203.0.113.2");

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(filter.trackedClientCount()).isEqualTo(1);
    }

    @Test
    void preflightAndWrongMethodRequestsDoNotConsumeLlmBudget()
            throws Exception {
        AtomicLong now = new AtomicLong(20_000L);
        RateLimitFilter filter = filter(now, 1);

        assertThat(perform(
                filter, "OPTIONS", "", "/api/analyze",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "HEAD", "", "/api/analyze-node",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "GET", "", "/api/analyze",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "POST", "", "/api/analyze",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "POST", "", "/api/analyze",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(429);
    }

    @Test
    void fixedWindowUsesMonotonicMinuteBoundary() throws Exception {
        AtomicLong now = new AtomicLong(30_000L);
        RateLimitFilter filter = filter(now, 1);

        assertThat(perform(
                filter, "POST", "", "/api/justify-leaf",
                "alice", "192.0.2.30", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "POST", "", "/api/justify-leaf",
                "alice", "192.0.2.30", null).getStatus()).isEqualTo(429);

        now.addAndGet(Duration.ofMinutes(1).toNanos());

        assertThat(perform(
                filter, "POST", "", "/api/justify-leaf",
                "alice", "192.0.2.30", null).getStatus()).isEqualTo(200);
    }

    @Test
    void expiredClientEntriesAreRemoved() throws Exception {
        AtomicLong now = new AtomicLong(40_000L);
        RateLimitFilter filter = filter(now, 1);

        for (int index = 0; index < 3; index++) {
            perform(filter, "GET", "", "/api/analyze-stream",
                    "user-" + index, "192.0.2." + index, null);
        }
        assertThat(filter.trackedClientCount()).isEqualTo(3);

        now.addAndGet(Duration.ofMinutes(2).toNanos());
        perform(filter, "GET", "", "/api/analyze-stream",
                "current-user", "192.0.2.100", null);

        assertThat(filter.trackedClientCount()).isEqualTo(1);
    }

    @Test
    void trackedClientStateHasAHardUpperBound() throws Exception {
        AtomicLong now = new AtomicLong(50_000L);
        RateLimitFilter filter = filter(now, 1);

        for (int index = 0; index < 10_050; index++) {
            perform(filter, "GET", "", "/api/analyze-node",
                    "principal-" + index, "192.0.2.1", null);
        }

        assertThat(filter.trackedClientCount()).isEqualTo(10_000);
    }

    @Test
    void negativeLimitFailsClosedInsteadOfDisablingProtection() throws Exception {
        AtomicLong now = new AtomicLong(60_000L);
        RateLimitFilter filter = filter(now, -7);

        assertThat(perform(
                filter, "POST", "", "/api/analyze",
                "alice", "192.0.2.60", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "POST", "", "/api/analyze",
                "alice", "192.0.2.60", null).getStatus()).isEqualTo(429);
    }

    @Test
    void zeroLimitDisablesTrackingAndLimiting() throws Exception {
        AtomicLong now = new AtomicLong(70_000L);
        RateLimitFilter filter = filter(now, 0);

        for (int index = 0; index < 3; index++) {
            assertThat(perform(
                    filter, "POST", "", "/api/analyze",
                    "alice", "192.0.2.70", null).getStatus()).isEqualTo(200);
        }
        assertThat(filter.trackedClientCount()).isZero();
    }

    private static RateLimitFilter filter(AtomicLong now, int limit) {
        RateLimitFilter filter = new RateLimitFilter(now::get);
        ReflectionTestUtils.setField(filter, "maxRequestsPerMinute", limit);
        return filter;
    }

    private static MockHttpServletResponse perform(
            RateLimitFilter filter,
            String method,
            String contextPath,
            String servletPath,
            String username,
            String remoteAddress,
            String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, contextPath + servletPath);
        request.setContextPath(contextPath);
        request.setServletPath(servletPath);
        request.setRemoteAddr(remoteAddress);
        if (username != null) {
            Principal principal = () -> username;
            request.setUserPrincipal(principal);
        }
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
