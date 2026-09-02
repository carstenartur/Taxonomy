package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static final List<SimpleGrantedAuthority> USER_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));

    @Test
    void contextPathAndForwardingHeadersCannotResetPrincipalBudget()
            throws Exception {
        AtomicLong now = new AtomicLong(1_000L);
        RateLimitFilter filter = filter(now, 1, 10);

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
        assertThat(filter.trackedPrincipalCount()).isEqualTo(2);
    }

    @Test
    void keycloakBrowserAndBearerShareImmutableIssuerSubjectBudget()
            throws Exception {
        AtomicLong now = new AtomicLong(5_000L);
        RateLimitFilter filter = filter(now, 1, 10);
        String issuer = "https://identity.example/realms/taxonomy";
        String subject = "550e8400-e29b-41d4-a716-446655440000";

        Authentication bearer = jwtAuthentication(
                issuer, subject, "old-display-name");
        Authentication browser = oidcAuthentication(
                issuer, subject, "renamed-display-name");
        Authentication otherSubject = jwtAuthentication(
                issuer, "different-subject", "old-display-name");
        Authentication otherIssuer = oidcAuthentication(
                "https://other-issuer.example/realms/taxonomy",
                subject,
                "renamed-display-name");

        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                bearer, "192.0.2.5", "203.0.113.1").getStatus())
                .isEqualTo(200);
        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                browser, "192.0.2.5", "203.0.113.99").getStatus())
                .isEqualTo(429);
        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                otherSubject, "192.0.2.5", null).getStatus())
                .isEqualTo(200);
        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                otherIssuer, "192.0.2.5", null).getStatus())
                .isEqualTo(200);
        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                "renamed-display-name", "192.0.2.5", null).getStatus())
                .isEqualTo(200);

        assertThat(filter.trackedPrincipalCount()).isEqualTo(4);
    }

    @Test
    void keycloakAuthenticationWithoutIssuerOrSubjectFailsClosedWithoutState()
            throws Exception {
        AtomicLong now = new AtomicLong(7_000L);
        RateLimitFilter filter = filter(now, 1, 10);
        Instant issuedAt = Instant.now();
        Jwt missingIssuer = Jwt.withTokenValue("missing-issuer")
                .header("alg", "RS256")
                .subject("stable-subject")
                .claim("preferred_username", "display-name")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .build();
        Jwt missingSubject = Jwt.withTokenValue("missing-subject")
                .header("alg", "RS256")
                .claim("iss", "https://identity.example/realms/taxonomy")
                .claim("preferred_username", "display-name")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(300))
                .build();

        for (Jwt jwt : List.of(missingIssuer, missingSubject)) {
            Authentication authentication = new JwtAuthenticationToken(
                    jwt, USER_AUTHORITIES, "display-name");
            MockHttpServletResponse response = perform(
                    filter, "GET", "", "/api/analyze-node",
                    authentication, "192.0.2.7", null);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        assertThat(filter.trackedPrincipalCount()).isZero();
    }

    @Test
    void unauthenticatedProtectedRequestFailsClosedWithoutAllocatingIdentity()
            throws Exception {
        AtomicLong now = new AtomicLong(10_000L);
        RateLimitFilter filter = filter(now, 3, 10);

        MockHttpServletResponse response = perform(
                filter, "GET", "", "/api/analyze-node",
                (Authentication) null, "192.0.2.10", "203.0.113.1");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("\"status\":401");
        assertThat(filter.trackedPrincipalCount()).isZero();
    }

    @Test
    void preflightHeadAndWrongMethodRequestsDoNotConsumeBudget()
            throws Exception {
        AtomicLong now = new AtomicLong(20_000L);
        RateLimitFilter filter = filter(now, 1, 10);

        assertThat(perform(
                filter, "OPTIONS", "", "/api/analyze",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "HEAD", "", "/api/analyze-node",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "GET", "", "/api/analyze",
                "alice", "192.0.2.20", null).getStatus()).isEqualTo(200);
        assertThat(filter.trackedPrincipalCount()).isZero();

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
        RateLimitFilter filter = filter(now, 1, 10);

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
    void expiredPrincipalEntriesAreRemoved() throws Exception {
        AtomicLong now = new AtomicLong(40_000L);
        RateLimitFilter filter = filter(now, 1, 10);

        for (int index = 0; index < 3; index++) {
            perform(filter, "GET", "", "/api/analyze-stream",
                    "user-" + index, "192.0.2." + index, null);
        }
        assertThat(filter.trackedPrincipalCount()).isEqualTo(3);

        now.addAndGet(Duration.ofMinutes(2).toNanos());
        perform(filter, "GET", "", "/api/analyze-stream",
                "current-user", "192.0.2.100", null);

        assertThat(filter.trackedPrincipalCount()).isEqualTo(1);
    }

    @Test
    void trackedPrincipalStateHasAHardBoundAndSharedOverflowFailsClosed()
            throws Exception {
        AtomicLong now = new AtomicLong(50_000L);
        RateLimitFilter filter = filter(now, 1, 2);

        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                "alice", "192.0.2.1", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                "bob", "192.0.2.1", null).getStatus()).isEqualTo(200);

        MockHttpServletResponse firstOverflow = perform(
                filter, "GET", "", "/api/analyze-node",
                "charlie", "192.0.2.1", null);
        MockHttpServletResponse secondOverflow = perform(
                filter, "GET", "", "/api/analyze-node",
                "dora", "192.0.2.1", null);

        assertThat(filter.trackedPrincipalCount()).isEqualTo(2);
        assertThat(firstOverflow.getStatus()).isEqualTo(200);
        assertThat(secondOverflow.getStatus()).isEqualTo(429);
    }

    @Test
    void capacityPressureUsesScheduledCleanupInsteadOfRepeatedFullSweeps()
            throws Exception {
        AtomicLong now = new AtomicLong(55_000L);
        RateLimitFilter filter = filter(now, 1, 2);

        perform(filter, "GET", "", "/api/analyze-node",
                "alice", "192.0.2.1", null);
        perform(filter, "GET", "", "/api/analyze-node",
                "bob", "192.0.2.1", null);
        assertThat(filter.cleanupSweepCount()).isZero();

        for (int index = 0; index < 100; index++) {
            perform(filter, "GET", "", "/api/analyze-node",
                    "overflow-" + index, "192.0.2.1", null);
        }
        assertThat(filter.trackedPrincipalCount()).isEqualTo(2);
        assertThat(filter.cleanupSweepCount()).isZero();

        now.addAndGet(RateLimitFilter.CLEANUP_INTERVAL_NANOS);
        perform(filter, "GET", "", "/api/analyze-node",
                "scheduled-sweep", "192.0.2.1", null);
        assertThat(filter.cleanupSweepCount()).isEqualTo(1L);
        assertThat(filter.trackedPrincipalCount()).isEqualTo(2);

        for (int index = 0; index < 25; index++) {
            perform(filter, "GET", "", "/api/analyze-node",
                    "same-interval-" + index, "192.0.2.1", null);
        }
        assertThat(filter.cleanupSweepCount()).isEqualTo(1L);

        now.addAndGet(RateLimitFilter.CLEANUP_INTERVAL_NANOS);
        MockHttpServletResponse admitted = perform(
                filter, "GET", "", "/api/analyze-node",
                "admitted-after-expiry", "192.0.2.1", null);

        assertThat(filter.cleanupSweepCount()).isEqualTo(2L);
        assertThat(filter.trackedPrincipalCount()).isEqualTo(1);
        assertThat(admitted.getStatus()).isEqualTo(200);
    }

    @Test
    void cleanupAndConcurrentReadmissionCannotCreateParallelFreshCounters()
            throws Exception {
        AtomicLong now = new AtomicLong(60_000L);
        RateLimitFilter filter = filter(now, 1, 10);
        assertThat(perform(
                filter, "GET", "", "/api/analyze-node",
                "alice", "192.0.2.60", null).getStatus()).isEqualTo(200);

        now.addAndGet(Duration.ofMinutes(2).toNanos());
        int callers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int index = 0; index < callers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return perform(
                            filter, "GET", "", "/api/analyze-node",
                            "alice", "192.0.2.60", null).getStatus();
                }));
            }
            ready.await();
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrderElementsOf(
                    expectedStatuses(callers));
            assertThat(filter.trackedPrincipalCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void negativeLimitFailsClosedInsteadOfDisablingProtection() throws Exception {
        AtomicLong now = new AtomicLong(70_000L);
        RateLimitFilter filter = filter(now, -7, 10);

        assertThat(perform(
                filter, "POST", "", "/api/analyze",
                "alice", "192.0.2.70", null).getStatus()).isEqualTo(200);
        assertThat(perform(
                filter, "POST", "", "/api/analyze",
                "alice", "192.0.2.70", null).getStatus()).isEqualTo(429);
    }

    @Test
    void zeroLimitDisablesTrackingAndLimiting() throws Exception {
        AtomicLong now = new AtomicLong(80_000L);
        RateLimitFilter filter = filter(now, 0, 10);

        for (int index = 0; index < 3; index++) {
            assertThat(perform(
                    filter, "POST", "", "/api/analyze",
                    (Authentication) null, "192.0.2.80", null).getStatus())
                    .isEqualTo(200);
        }
        assertThat(filter.trackedPrincipalCount()).isZero();
    }

    private static Authentication jwtAuthentication(
            String issuer,
            String subject,
            String preferredUsername) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("jwt-" + subject + '-' + preferredUsername)
                .header("alg", "RS256")
                .claim("iss", issuer)
                .subject(subject)
                .claim("preferred_username", preferredUsername)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(
                jwt, USER_AUTHORITIES, preferredUsername);
    }

    private static Authentication oidcAuthentication(
            String issuer,
            String subject,
            String preferredUsername) {
        Instant now = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "id-" + subject + '-' + preferredUsername,
                now,
                now.plusSeconds(300),
                Map.of(
                        "iss", issuer,
                        "sub", subject,
                        "preferred_username", preferredUsername));
        DefaultOidcUser user = new DefaultOidcUser(
                USER_AUTHORITIES, idToken, "preferred_username");
        return new OAuth2AuthenticationToken(
                user, USER_AUTHORITIES, "keycloak");
    }

    private static List<Integer> expectedStatuses(int callers) {
        List<Integer> statuses = new ArrayList<>();
        statuses.add(200);
        for (int index = 1; index < callers; index++) {
            statuses.add(429);
        }
        return statuses;
    }

    private static RateLimitFilter filter(
            AtomicLong now,
            int limit,
            int maxTrackedPrincipals) {
        RateLimitFilter filter = new RateLimitFilter(
                now::get, maxTrackedPrincipals);
        ReflectionTestUtils.setField(
                filter, "maxRequestsPerMinute", limit);
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
        Authentication authentication = username == null
                ? null
                : new TestingAuthenticationToken(
                        username, "[PROTECTED]", "ROLE_USER");
        return perform(
                filter,
                method,
                contextPath,
                servletPath,
                authentication,
                remoteAddress,
                forwardedFor);
    }

    private static MockHttpServletResponse perform(
            RateLimitFilter filter,
            String method,
            String contextPath,
            String servletPath,
            Authentication authentication,
            String remoteAddress,
            String forwardedFor) throws Exception {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        if (authentication != null) {
            context.setAuthentication(authentication);
        }
        SecurityContextHolder.setContext(context);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    method, contextPath + servletPath);
            request.setContextPath(contextPath);
            request.setServletPath(servletPath);
            request.setRemoteAddr(remoteAddress);
            if (forwardedFor != null) {
                request.addHeader("X-Forwarded-For", forwardedFor);
            }

            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            return response;
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
