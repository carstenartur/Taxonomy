package com.taxonomy.security.webdav;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialFilterTest {

    private static final String TOKEN =
            "taxdav_" + "a".repeat(24) + "_" + "A".repeat(39);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesAnApplicationSecretForTheFilterChainAndErasesItAfterward()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        assertThat(TOKEN).hasSize(71);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.of(principal(true)));
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request("GET");
        request.setContextPath("/taxonomy");
        request.setRequestURI("/taxonomy/dav/templates/report.dotx");
        request.addHeader("Authorization", basic("admin", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(
                SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        verify(service).authenticate("admin", TOKEN);
        assertThat(observed.get().getName()).isEqualTo("admin");
        assertThat(observed.get().getCredentials()).isEqualTo("[PROTECTED]");
        assertThat(observed.get().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_ADMIN", "SCOPE_template:write");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void readOnlyCredentialCannotPutLockOrUnlock() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.of(principal(false)));
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request("PUT");
        request.addHeader("Authorization", basic("admin", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(called).isFalse();
    }

    @Test
    void invalidOrAbsentCredentialGetsABasicChallengeWithoutLoginHtml()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.empty());
        WebDavApplicationCredentialFilter filter = filter(service);

        MockHttpServletRequest invalid = request("GET");
        invalid.addHeader("Authorization", basic("admin", TOKEN));
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, (req, res) -> { });
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
        assertThat(invalidResponse.getHeader("WWW-Authenticate"))
                .startsWith("Basic realm=\"Taxonomy WebDAV\"");

        MockHttpServletRequest absent = request("OPTIONS");
        MockHttpServletResponse absentResponse = new MockHttpServletResponse();
        filter.doFilter(absent, absentResponse, (req, res) -> { });
        assertThat(absentResponse.getStatus()).isEqualTo(401);
        assertThat(absentResponse.getContentAsString()).doesNotContain("<html");
    }

    @Test
    void ordinaryLocalAccountBasicAuthenticationFallsThroughToTheExistingFilter()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request("GET");
        request.addHeader("Authorization", basic("admin", "ordinary-password"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> called.set(true));

        assertThat(called).isTrue();
        verify(service, never()).authenticate("admin", "ordinary-password");
    }

    @Test
    void malformedOrOversizedApplicationCandidatesNeverReachCredentialService()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        WebDavApplicationCredentialFilter filter = filter(service);

        MockHttpServletRequest malformed = request("GET");
        malformed.addHeader("Authorization", basic("admin", "taxdav_invalid"));
        MockHttpServletResponse malformedResponse = new MockHttpServletResponse();
        filter.doFilter(malformed, malformedResponse, (req, res) -> { });
        assertThat(malformedResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest oversizedPassword = request("GET");
        oversizedPassword.addHeader("Authorization", basic("admin", "taxdav_" + "x".repeat(400)));
        MockHttpServletResponse oversizedPasswordResponse = new MockHttpServletResponse();
        filter.doFilter(oversizedPassword, oversizedPasswordResponse, (req, res) -> { });
        assertThat(oversizedPasswordResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest oversizedUsername = request("GET");
        oversizedUsername.addHeader("Authorization", basic("u".repeat(257), TOKEN));
        MockHttpServletResponse oversizedUsernameResponse = new MockHttpServletResponse();
        filter.doFilter(oversizedUsername, oversizedUsernameResponse, (req, res) -> { });
        assertThat(oversizedUsernameResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest oversizedHeader = request("GET");
        oversizedHeader.addHeader("Authorization", "Basic " + "A".repeat(2000));
        MockHttpServletResponse oversizedHeaderResponse = new MockHttpServletResponse();
        filter.doFilter(oversizedHeader, oversizedHeaderResponse, (req, res) -> { });
        assertThat(oversizedHeaderResponse.getStatus()).isEqualTo(401);

        verify(service, never()).authenticate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void tenFailedAttemptsLockSamePeerAndUsernameAndReturnJson429() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.empty());
        WebDavApplicationCredentialFilter filter = filter(service);

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = failedRequest(filter, "admin", TOKEN, "10.0.0.1");
            assertThat(response.getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse lockedResponse = failedRequest(filter, "admin", TOKEN, "10.0.0.1");
        assertThat(lockedResponse.getStatus()).isEqualTo(429);
        assertThat(lockedResponse.getHeader("Retry-After")).isEqualTo("60");
        assertThat(lockedResponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(lockedResponse.getContentType()).startsWith("application/json");
        assertThat(lockedResponse.getContentAsString())
                .contains("too_many_failed_webdav_authentication_attempts")
                .doesNotContain("admin")
                .doesNotContain("taxdav_")
                .doesNotContain("10.0.0.1");
    }

    @Test
    void successfulAuthenticationClearsOnlyMatchingFailureState() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        AtomicInteger adminCalls = new AtomicInteger();
        when(service.authenticate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
                    String username = invocation.getArgument(0, String.class);
                    if ("admin".equals(username) && adminCalls.getAndIncrement() == 1) {
                        return Optional.of(principal(true));
                    }
                    return Optional.empty();
                });
        WebDavApplicationCredentialFilter filter = filter(service);

        failedRequest(filter, "admin", TOKEN, "10.0.0.1");
        failedRequest(filter, "other", TOKEN, "10.0.0.1");
        successfulRequest(filter, "admin", TOKEN, "10.0.0.1");

        for (int i = 0; i < 9; i++) {
            assertThat(failedRequest(filter, "admin", TOKEN, "10.0.0.1").getStatus())
                    .isEqualTo(401);
        }
        for (int i = 0; i < 8; i++) {
            assertThat(failedRequest(filter, "other", TOKEN, "10.0.0.1").getStatus())
                    .isEqualTo(401);
        }
        assertThat(failedRequest(filter, "other", TOKEN, "10.0.0.1").getStatus())
                .isEqualTo(401);
        assertThat(failedRequest(filter, "other", TOKEN, "10.0.0.1").getStatus())
                .isEqualTo(429);
    }

    @Test
    void inactiveKeysExpireUsingMonotonicTime() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN)).thenReturn(Optional.empty());
        MutableNanoTime clock = new MutableNanoTime();
        WebDavApplicationCredentialFilter filter = filter(service, clock);

        for (int i = 0; i < 10; i++) {
            assertThat(failedRequest(filter, "admin", TOKEN, "10.0.0.1").getStatus())
                    .isEqualTo(401);
        }
        assertThat(failedRequest(filter, "admin", TOKEN, "10.0.0.1").getStatus())
                .isEqualTo(429);

        clock.advance(Duration.ofMinutes(1).toNanos() + 1);
        assertThat(failedRequest(filter, "admin", TOKEN, "10.0.0.1").getStatus())
                .isEqualTo(401);
    }

    @Test
    void hardCapacityIsNeverExceededUnderConcurrentNewIdentities() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        WebDavApplicationCredentialFilter filter = filter(service);
        int threads = 24;
        int attemptsPerThread = 600;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                pool.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int i = 0; i < attemptsPerThread; i++) {
                            String username = "user-" + thread + "-" + i;
                            failedRequest(filter, username, "taxdav_invalid", "10.0.0.1");
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(filter.trackedFailureKeyCount()).isLessThanOrEqualTo(10_000);
        verify(service, never()).authenticate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void overflowBucketIsFailClosedAndDoesNotEraseExistingLockouts() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        WebDavApplicationCredentialFilter filter = filter(service);

        for (int i = 0; i < 10; i++) {
            assertThat(failedRequest(filter, "protected", TOKEN, "10.0.0.1").getStatus())
                    .isEqualTo(401);
        }
        assertThat(failedRequest(filter, "protected", TOKEN, "10.0.0.1").getStatus())
                .isEqualTo(429);

        for (int i = 0; i < 9_998; i++) {
            assertThat(failedRequest(filter, "seed-" + i, "taxdav_invalid", "10.0.0.1")
                    .getStatus()).isEqualTo(401);
        }
        assertThat(filter.trackedFailureKeyCount()).isLessThanOrEqualTo(10_000);

        for (int i = 0; i < 10; i++) {
            assertThat(failedRequest(filter, "overflow-a-" + i, "taxdav_invalid", "10.0.0.1")
                    .getStatus()).isEqualTo(401);
        }
        assertThat(failedRequest(filter, "overflow-b", "taxdav_invalid", "10.0.0.1").getStatus())
                .isEqualTo(429);
        assertThat(failedRequest(filter, "protected", TOKEN, "10.0.0.1").getStatus())
                .isEqualTo(429);
        assertThat(filter.trackedFailureKeyCount()).isLessThanOrEqualTo(10_000);
    }

    private static WebDavApplicationCredentialFilter filter(
            WebDavApplicationCredentialService service) {
        return new WebDavApplicationCredentialFilter(
                service);
    }

    private static WebDavApplicationCredentialFilter filter(
            WebDavApplicationCredentialService service,
            LongSupplier nanoTime) {
        return new WebDavApplicationCredentialFilter(service, nanoTime);
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, "/dav/templates/report.dotx");
        request.setRequestURI("/dav/templates/report.dotx");
        return request;
    }

    private static WebDavApplicationCredentialService.CredentialPrincipal principal(
            boolean write) {
        List<SimpleGrantedAuthority> authorities = write
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_template:read"),
                    new SimpleGrantedAuthority("SCOPE_template:write"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("SCOPE_template:read"));
        return new WebDavApplicationCredentialService.CredentialPrincipal(
                "a".repeat(24), "admin", true, write, List.copyOf(authorities));
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletResponse failedRequest(
            WebDavApplicationCredentialFilter filter,
            String username,
            String password,
            String remoteAddress) throws Exception {
        MockHttpServletRequest request = request("GET");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("Authorization", basic(username, password));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response;
    }

    private static MockHttpServletResponse successfulRequest(
            WebDavApplicationCredentialFilter filter,
            String username,
            String password,
            String remoteAddress) throws Exception {
        MockHttpServletRequest request = request("GET");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("Authorization", basic(username, password));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response;
    }

    private static final class MutableNanoTime implements LongSupplier {
        private final AtomicLong now = new AtomicLong();

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(long deltaNanos) {
            now.addAndGet(deltaNanos);
        }
    }
}
