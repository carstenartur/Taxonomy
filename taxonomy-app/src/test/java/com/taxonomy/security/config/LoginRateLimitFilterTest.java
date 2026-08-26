package com.taxonomy.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimitFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAndBearerCredentialsDoNotAllocateFailureState() throws Exception {
        LoginRateLimitFilter filter = filter(3, 60);

        invoke(filter, request("GET", "/api/taxonomy", "203.0.113.10"),
                response -> response.setStatus(401));
        MockHttpServletRequest bearer = request(
                "GET", "/api/taxonomy", "203.0.113.10");
        bearer.addHeader(HttpHeaders.AUTHORIZATION, "Bearer rejected-token");
        invoke(filter, bearer, response -> response.setStatus(401));

        assertThat(filter.trackedPeerCount()).isZero();
        assertThat(filter.overflowFailureCount()).isZero();
    }

    @Test
    void basicFailuresLockPeerAndReturnExplicitNoStoreJson() throws Exception {
        LoginRateLimitFilter filter = filter(2, 60);
        String peer = "203.0.113.20";

        basicFailure(filter, peer, null);
        basicFailure(filter, peer, null);

        AtomicInteger downstreamCalls = new AtomicInteger();
        MockHttpServletResponse response = invoke(
                filter,
                basicRequest("/api/taxonomy", peer, null),
                ignored -> downstreamCalls.incrementAndGet());

        assertThat(downstreamCalls).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(423);
        long retryAfter = Long.parseLong(
                response.getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"status\":423")
                .contains("\"retryAfterSeconds\":" + retryAfter)
                .doesNotContain(peer)
                .doesNotContain("Basic");
    }

    @Test
    void authenticatedSessionBypassesLockedPeer() throws Exception {
        LoginRateLimitFilter filter = filter(2, 60);
        String peer = "203.0.113.30";
        basicFailure(filter, peer, null);
        basicFailure(filter, peer, null);

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "alice", "[PROTECTED]",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        AtomicInteger downstreamCalls = new AtomicInteger();
        MockHttpServletResponse response = invoke(
                filter,
                basicRequest("/api/taxonomy", peer, null),
                target -> {
                    downstreamCalls.incrementAndGet();
                    target.setStatus(200);
                });

        assertThat(downstreamCalls).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void successfulAuthenticationClearsOnlyItsPeerFailureState() throws Exception {
        LoginRateLimitFilter filter = filter(2, 60);
        String successfulPeer = "203.0.113.35";
        String otherPeer = "203.0.113.36";
        basicFailure(filter, successfulPeer, null);
        basicFailure(filter, otherPeer, null);
        assertThat(filter.trackedPeerCount()).isEqualTo(2);

        invoke(filter,
                basicRequest("/api/taxonomy", successfulPeer, null),
                response -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    "alice", "[PROTECTED]",
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                    response.setStatus(200);
                });

        assertThat(filter.trackedPeerCount()).isEqualTo(1);
        SecurityContextHolder.clearContext();
        basicFailure(filter, otherPeer, null);
        MockHttpServletResponse otherPeerResponse = invoke(
                filter,
                basicRequest("/api/taxonomy", otherPeer, null),
                response -> response.setStatus(200));
        assertThat(otherPeerResponse.getStatus()).isEqualTo(423);
    }

    @Test
    void forwardingHeaderCannotCreateAnotherPeerIdentity() throws Exception {
        LoginRateLimitFilter filter = filter(2, 60);
        String peer = "203.0.113.40";

        basicFailure(filter, peer, "198.51.100.10");
        basicFailure(filter, peer, "198.51.100.99");
        MockHttpServletResponse response = invoke(
                filter,
                basicRequest("/api/taxonomy", peer, "192.0.2.5"),
                target -> target.setStatus(200));

        assertThat(filter.trackedPeerCount()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(423);
    }

    @Test
    void servletContextPathUsesTheSameBasicAttemptContract() throws Exception {
        LoginRateLimitFilter filter = filter(2, 60);
        String peer = "203.0.113.50";

        basicFailure(filter, "/taxonomy/api/taxonomy",
                "/taxonomy", "/api/taxonomy", peer);
        basicFailure(filter, "/taxonomy/api/taxonomy",
                "/taxonomy", "/api/taxonomy", peer);
        MockHttpServletResponse response = invoke(
                filter,
                basicRequest("/taxonomy/api/taxonomy", "/taxonomy",
                        "/api/taxonomy", peer),
                target -> target.setStatus(200));

        assertThat(response.getStatus()).isEqualTo(423);
    }

    @Test
    void stalePeersExpireUsingMonotonicTimeBeforeCapacityOverflows() throws Exception {
        AtomicLong clock = new AtomicLong();
        LoginRateLimitFilter filter = new LoginRateLimitFilter(
                clock::get, 1, 2, 60);

        basicFailure(filter, "203.0.113.60", null);
        clock.addAndGet(Duration.ofSeconds(61).toNanos());
        basicFailure(filter, "203.0.113.61", null);

        assertThat(filter.trackedPeerCount()).isEqualTo(1);
        assertThat(filter.overflowFailureCount()).isZero();
    }

    @Test
    void concurrentNewPeersNeverExceedCapacityAndOverflowFailsClosed()
            throws Exception {
        int capacity = 8;
        LoginRateLimitFilter filter = new LoginRateLimitFilter(
                System::nanoTime, capacity, 2, 60);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        basicFailure(filter, "198.51.100." + index, null);
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(filter.trackedPeerCount()).isLessThanOrEqualTo(capacity);
        assertThat(filter.overflowFailureCount()).isGreaterThanOrEqualTo(2);
        MockHttpServletResponse overflowResponse = invoke(
                filter,
                basicRequest("/api/taxonomy", "192.0.2.250", null),
                target -> target.setStatus(200));
        assertThat(overflowResponse.getStatus()).isEqualTo(423);
    }

    @Test
    void invalidConfigurationFailsClosedAtConstruction() {
        assertThatThrownBy(() -> new LoginRateLimitFilter(
                System::nanoTime, 10, 0, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new LoginRateLimitFilter(
                System::nanoTime, 10, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockout-seconds");
        assertThatThrownBy(() -> new LoginRateLimitFilter(
                System::nanoTime, 0, 2, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTrackedPeers");
    }

    private static LoginRateLimitFilter filter(
            int maximumAttempts,
            int lockoutSeconds) {
        return new LoginRateLimitFilter(
                System::nanoTime, 100, maximumAttempts, lockoutSeconds);
    }

    private static void basicFailure(
            LoginRateLimitFilter filter,
            String peer,
            String forwardedFor) throws Exception {
        basicFailure(filter, "/api/taxonomy", "", "/api/taxonomy",
                peer, forwardedFor);
    }

    private static void basicFailure(
            LoginRateLimitFilter filter,
            String requestUri,
            String contextPath,
            String servletPath,
            String peer) throws Exception {
        basicFailure(filter, requestUri, contextPath, servletPath, peer, null);
    }

    private static void basicFailure(
            LoginRateLimitFilter filter,
            String requestUri,
            String contextPath,
            String servletPath,
            String peer,
            String forwardedFor) throws Exception {
        invoke(filter,
                basicRequest(requestUri, contextPath, servletPath,
                        peer, forwardedFor),
                response -> response.setStatus(401));
    }

    private static MockHttpServletRequest basicRequest(
            String path,
            String peer,
            String forwardedFor) {
        return basicRequest(path, "", path, peer, forwardedFor);
    }

    private static MockHttpServletRequest basicRequest(
            String requestUri,
            String contextPath,
            String servletPath,
            String peer) {
        return basicRequest(requestUri, contextPath, servletPath, peer, null);
    }

    private static MockHttpServletRequest basicRequest(
            String requestUri,
            String contextPath,
            String servletPath,
            String peer,
            String forwardedFor) {
        MockHttpServletRequest request = request("GET", requestUri, peer);
        request.setContextPath(contextPath);
        request.setServletPath(servletPath);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dGVzdDpiYWQ=");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static MockHttpServletRequest request(
            String method,
            String path,
            String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setServletPath(path);
        request.setRemoteAddr(peer);
        return request;
    }

    private static MockHttpServletResponse invoke(
            LoginRateLimitFilter filter,
            MockHttpServletRequest request,
            ResponseAction action) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                action.accept((MockHttpServletResponse) servletResponse);
        filter.doFilter(request, response, chain);
        return response;
    }

    @FunctionalInterface
    private interface ResponseAction {
        void accept(MockHttpServletResponse response)
                throws IOException, ServletException;
    }
}
