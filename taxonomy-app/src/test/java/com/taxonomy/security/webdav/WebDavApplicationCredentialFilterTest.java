package com.taxonomy.security.webdav;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialFilterTest {

    private static final String TOKEN =
            token("a".repeat(24), 'A');

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesExactProductionSecretForContextPathAndErasesContextAfterward()
            throws Exception {
        assertThat(TOKEN)
                .hasSize(WebDavApplicationCredentialService.TOKEN_LENGTH);

        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN))
                .thenReturn(Optional.of(principal("admin", true)));
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request(
                "GET", "203.0.113.10", "admin", TOKEN);
        request.setContextPath("/taxonomy");
        request.setRequestURI(
                "/taxonomy/dav/templates/report.dotx");
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        AtomicReference<Authentication> observed =
                new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(
                SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(observed.get().getName()).isEqualTo("admin");
        assertThat(observed.get().getCredentials())
                .isEqualTo("[PROTECTED]");
        assertThat(observed.get().getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_ADMIN", "SCOPE_template:write");
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
        verify(service).authenticate("admin", TOKEN);
    }

    @Test
    void readOnlyCredentialCannotPutLockOrUnlock() throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN))
                .thenReturn(Optional.of(principal("admin", false)));
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request(
                "PUT", "203.0.113.11", "admin", TOKEN);
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(
                request, response, (req, res) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(called).isFalse();
    }

    @Test
    void invalidOrAbsentCredentialGetsBasicChallengeWithoutLoginHtml()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate("admin", TOKEN))
                .thenReturn(Optional.empty());
        WebDavApplicationCredentialFilter filter = filter(service);

        MockHttpServletRequest invalid = request(
                "GET", "203.0.113.12", "admin", TOKEN);
        MockHttpServletResponse invalidResponse =
                new MockHttpServletResponse();
        filter.doFilter(invalid, invalidResponse, (req, res) -> { });
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
        assertThat(invalidResponse.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Basic realm=\"Taxonomy WebDAV\"");

        MockHttpServletRequest absent = request("OPTIONS");
        MockHttpServletResponse absentResponse =
                new MockHttpServletResponse();
        filter.doFilter(absent, absentResponse, (req, res) -> { });
        assertThat(absentResponse.getStatus()).isEqualTo(401);
        assertThat(absentResponse.getContentAsString())
                .doesNotContain("<html");
    }

    @Test
    void ordinaryLocalAccountBasicAuthenticationFallsThrough()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        WebDavApplicationCredentialFilter filter = filter(service);
        MockHttpServletRequest request = request(
                "GET",
                "203.0.113.13",
                "admin",
                "ordinary-password");
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(
                request, response, (req, res) -> called.set(true));

        assertThat(called).isTrue();
        verifyNoInteractions(service);
    }

    @Test
    void oversizedAndMalformedApplicationCandidatesStopBeforeCredentialService()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        WebDavApplicationCredentialFilter filter = filter(service);
        FilterChain chain = mock(FilterChain.class);

        List<MockHttpServletRequest> rejected = List.of(
                requestWithAuthorization(
                        "Basic " + "A".repeat(
                                WebDavApplicationCredentialFilter
                                        .MAX_AUTHORIZATION_HEADER_CHARS)),
                requestWithAuthorization(oversizedDecodedAuthorization()),
                requestWithAuthorization("Basic /w=="),
                request(
                        "GET",
                        "203.0.113.20",
                        "u".repeat(
                                WebDavApplicationCredentialFilter
                                        .MAX_USERNAME_CODE_POINTS + 1),
                        TOKEN),
                request(
                        "GET",
                        "203.0.113.21",
                        "admin",
                        "taxdav_" + "A".repeat(
                                WebDavApplicationCredentialFilter
                                        .MAX_PASSWORD_CODE_POINTS)),
                request(
                        "GET",
                        "203.0.113.22",
                        "admin",
                        TOKEN.substring(0, TOKEN.length() - 1) + "!"));

        for (MockHttpServletRequest request : rejected) {
            MockHttpServletResponse response =
                    new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .startsWith("Basic realm=\"Taxonomy WebDAV\"");
        }

        verifyNoInteractions(service);
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tenFailuresLockIdentityAndReturnSanitizedJson429()
            throws Exception {
        WebDavApplicationCredentialService service =
                rejectingService();
        WebDavApplicationCredentialFilter filter = filter(service);
        String peer = "203.0.113.30";
        String username = "sensitive-user";

        for (int attempt = 0;
                attempt < WebDavApplicationCredentialFilter.MAX_FAILURES;
                attempt++) {
            MockHttpServletRequest failed =
                    request("GET", peer, username, TOKEN);
            failed.addHeader(
                    "X-Forwarded-For",
                    "198.51.100." + attempt);
            MockHttpServletResponse response =
                    execute(filter, failed);
            assertThat(response.getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse limited = execute(
                filter, request("GET", peer, username, TOKEN));

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER))
                .isEqualTo("60");
        assertThat(limited.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(limited.getContentType())
                .startsWith("application/json");
        assertThat(limited.getContentAsString())
                .contains("\"status\":429")
                .contains("\"retryAfterSeconds\":60")
                .doesNotContain(username)
                .doesNotContain(peer)
                .doesNotContain(TOKEN);
        verify(service, times(
                WebDavApplicationCredentialFilter.MAX_FAILURES))
                .authenticate(username, TOKEN);
    }

    @Test
    void successfulAuthenticationClearsOnlyItsOwnTrackedIdentity()
            throws Exception {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        AtomicBoolean aliceSucceeds = new AtomicBoolean();
        when(service.authenticate(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String username = invocation.getArgument(0);
                    if ("alice".equals(username)
                            && aliceSucceeds.get()) {
                        return Optional.of(principal("alice", true));
                    }
                    return Optional.empty();
                });
        WebDavApplicationCredentialFilter filter = filter(service);

        for (int attempt = 0; attempt < 9; attempt++) {
            assertThat(execute(
                    filter,
                    request(
                            "GET",
                            "203.0.113.40",
                            "alice",
                            TOKEN))
                    .getStatus()).isEqualTo(401);
            assertThat(execute(
                    filter,
                    request(
                            "GET",
                            "203.0.113.41",
                            "bob",
                            TOKEN))
                    .getStatus()).isEqualTo(401);
        }

        aliceSucceeds.set(true);
        AtomicBoolean called = new AtomicBoolean();
        MockHttpServletResponse success =
                new MockHttpServletResponse();
        filter.doFilter(
                request("GET", "203.0.113.40", "alice", TOKEN),
                success,
                (req, res) -> called.set(true));
        assertThat(called).isTrue();

        aliceSucceeds.set(false);
        assertThat(execute(
                filter,
                request("GET", "203.0.113.40", "alice", TOKEN))
                .getStatus()).isEqualTo(401);

        assertThat(execute(
                filter,
                request("GET", "203.0.113.41", "bob", TOKEN))
                .getStatus()).isEqualTo(401);
        assertThat(execute(
                filter,
                request("GET", "203.0.113.41", "bob", TOKEN))
                .getStatus()).isEqualTo(429);
    }

    @Test
    void inactiveTrackedKeysExpireUsingMonotonicTime()
            throws Exception {
        WebDavApplicationCredentialService service =
                rejectingService();
        AtomicLong now = new AtomicLong();
        WebDavApplicationCredentialFilter filter =
                filter(service, now, 4);
        MockHttpServletRequest request = request(
                "GET", "203.0.113.50", "admin", TOKEN);

        for (int attempt = 0;
                attempt < WebDavApplicationCredentialFilter.MAX_FAILURES;
                attempt++) {
            assertThat(execute(filter, request(
                    "GET", "203.0.113.50", "admin", TOKEN))
                    .getStatus()).isEqualTo(401);
        }
        assertThat(execute(filter, request)
                .getStatus()).isEqualTo(429);

        now.addAndGet(
                WebDavApplicationCredentialFilter.FAILURE_WINDOW_NANOS + 1);

        assertThat(execute(
                filter,
                request("GET", "203.0.113.50", "admin", TOKEN))
                .getStatus()).isEqualTo(401);
        assertThat(filter.trackedFailureKeyCount()).isEqualTo(1);
    }

    @Test
    void concurrentIdentityFloodNeverExceedsHardCapacity()
            throws Exception {
        int capacity = 8;
        int identities = 64;
        WebDavApplicationCredentialService service =
                rejectingService();
        WebDavApplicationCredentialFilter filter =
                filter(service, new AtomicLong(), capacity);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        try {
            for (int identity = 0; identity < identities; identity++) {
                int current = identity;
                results.add(executor.submit(() -> {
                    start.await();
                    MockHttpServletResponse response = execute(
                            filter,
                            request(
                                    "GET",
                                    "198.51.100." + current,
                                    "user-" + current,
                                    TOKEN));
                    return response.getStatus();
                }));
            }

            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS))
                        .isIn(401, 429);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(filter.trackedFailureKeyCount())
                .isEqualTo(capacity);
        assertThat(filter.overflowFailureCount())
                .isEqualTo(
                        WebDavApplicationCredentialFilter.MAX_FAILURES);
    }

    @Test
    void overflowIdentitiesShareFailClosedBudgetWithoutErasingTrackedLockout()
            throws Exception {
        WebDavApplicationCredentialService service =
                rejectingService();
        WebDavApplicationCredentialFilter filter =
                filter(service, new AtomicLong(), 1);

        fail(
                filter,
                "203.0.113.60",
                "tracked",
                WebDavApplicationCredentialFilter.MAX_FAILURES);
        fail(
                filter,
                "203.0.113.61",
                "overflow-one",
                WebDavApplicationCredentialFilter.MAX_FAILURES);

        MockHttpServletResponse otherOverflow = execute(
                filter,
                request(
                        "GET",
                        "203.0.113.62",
                        "overflow-two",
                        TOKEN));
        MockHttpServletResponse tracked = execute(
                filter,
                request(
                        "GET",
                        "203.0.113.60",
                        "tracked",
                        TOKEN));

        assertThat(otherOverflow.getStatus()).isEqualTo(429);
        assertThat(tracked.getStatus()).isEqualTo(429);
        assertThat(filter.trackedFailureKeyCount()).isEqualTo(1);
        assertThat(filter.overflowFailureCount())
                .isEqualTo(
                        WebDavApplicationCredentialFilter.MAX_FAILURES);
    }

    private static void fail(
            WebDavApplicationCredentialFilter filter,
            String peer,
            String username,
            int count) throws Exception {
        for (int attempt = 0; attempt < count; attempt++) {
            assertThat(execute(
                    filter,
                    request("GET", peer, username, TOKEN))
                    .getStatus()).isEqualTo(401);
        }
    }

    private static WebDavApplicationCredentialService rejectingService() {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        when(service.authenticate(anyString(), anyString()))
                .thenReturn(Optional.empty());
        return service;
    }

    private static WebDavApplicationCredentialFilter filter(
            WebDavApplicationCredentialService service) {
        return filter(
                service,
                new AtomicLong(),
                WebDavApplicationCredentialFilter
                        .DEFAULT_MAX_TRACKED_FAILURE_KEYS);
    }

    private static WebDavApplicationCredentialFilter filter(
            WebDavApplicationCredentialService service,
            AtomicLong now,
            int capacity) {
        return new WebDavApplicationCredentialFilter(
                service, now::get, capacity);
    }

    private static MockHttpServletResponse execute(
            WebDavApplicationCredentialFilter filter,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response =
                new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { });
        return response;
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        method, "/dav/templates/report.dotx");
        request.setRequestURI("/dav/templates/report.dotx");
        request.setRemoteAddr("203.0.113.1");
        return request;
    }

    private static MockHttpServletRequest request(
            String method,
            String peer,
            String username,
            String password) {
        MockHttpServletRequest request = request(method);
        request.setRemoteAddr(peer);
        request.addHeader(
                HttpHeaders.AUTHORIZATION,
                basic(username, password));
        return request;
    }

    private static MockHttpServletRequest requestWithAuthorization(
            String authorization) {
        MockHttpServletRequest request = request("GET");
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        return request;
    }

    private static String oversizedDecodedAuthorization() {
        byte[] decoded = new byte[
                WebDavApplicationCredentialFilter
                        .MAX_DECODED_CREDENTIAL_BYTES + 1];
        return "Basic "
                + Base64.getEncoder().encodeToString(decoded);
    }

    private static WebDavApplicationCredentialService.CredentialPrincipal principal(
            String username,
            boolean write) {
        List<SimpleGrantedAuthority> authorities = write
                ? List.of(
                    new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("SCOPE_template:read"),
                    new SimpleGrantedAuthority("SCOPE_template:write"))
                : List.of(
                    new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("SCOPE_template:read"));
        return new WebDavApplicationCredentialService.CredentialPrincipal(
                "a".repeat(24),
                username,
                true,
                write,
                List.copyOf(authorities));
    }

    private static String token(String id, char secretCharacter) {
        return "taxdav_"
                + id
                + "_"
                + String.valueOf(secretCharacter).repeat(39);
    }

    private static String basic(String username, String password) {
        return "Basic "
                + Base64.getEncoder().encodeToString(
                        (username + ":" + password)
                                .getBytes(StandardCharsets.UTF_8));
    }
}
