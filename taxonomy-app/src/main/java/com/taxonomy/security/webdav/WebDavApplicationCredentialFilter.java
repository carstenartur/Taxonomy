package com.taxonomy.security.webdav;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Authenticates revocable WebDAV app credentials without exposing account passwords. */
public final class WebDavApplicationCredentialFilter extends OncePerRequestFilter {

    static final int MAX_FAILURES = 10;
    static final long FAILURE_WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    static final int DEFAULT_MAX_TRACKED_FAILURE_KEYS = 10_000;
    static final int MAX_AUTHORIZATION_HEADER_CHARS = 4_096;
    static final int MAX_DECODED_CREDENTIAL_BYTES = 2_048;
    static final int MAX_USERNAME_CODE_POINTS = 255;
    static final int MAX_PASSWORD_CODE_POINTS = 255;

    private static final Logger log =
            LoggerFactory.getLogger(WebDavApplicationCredentialFilter.class);
    private static final String BASIC_PREFIX = "Basic ";
    private static final long CLEANUP_INTERVAL_NANOS =
            Duration.ofSeconds(30).toNanos();
    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final WebDavApplicationCredentialService credentials;
    private final LongSupplier monotonicNanos;
    private final int maxTrackedFailureKeys;
    private final Object stateMonitor = new Object();
    private final Map<String, FailureTracker> failures = new HashMap<>();
    private final FailureTracker overflowFailures = new FailureTracker();
    private long nextCleanupAt;

    public WebDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials) {
        this(credentials, System::nanoTime, DEFAULT_MAX_TRACKED_FAILURE_KEYS);
    }

    WebDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials,
            LongSupplier monotonicNanos,
            int maxTrackedFailureKeys) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.monotonicNanos =
                Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        if (maxTrackedFailureKeys <= 0) {
            throw new IllegalArgumentException(
                    "maxTrackedFailureKeys must be positive");
        }
        this.maxTrackedFailureKeys = maxTrackedFailureKeys;
        long now = monotonicNanos.getAsLong();
        this.nextCleanupAt = now + CLEANUP_INTERVAL_NANOS;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isTemplateWebDavRequest(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        BasicParseResult parsed = parseBasic(authorization);

        if (!parsed.basicScheme()) {
            if (authorization == null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                unauthorized(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (parsed.credential() == null) {
            unauthorized(response);
            return;
        }

        BasicCredential supplied = parsed.credential();
        if (!WebDavApplicationCredentialService.isApplicationSecret(
                supplied.password())) {
            chain.doFilter(request, response);
            return;
        }
        if (!WebDavApplicationCredentialService.hasExactApplicationSecretFormat(
                supplied.password())) {
            unauthorized(response);
            return;
        }

        String failureKey = failureKey(request, supplied.username());
        LockState currentLock =
                currentLock(failureKey, monotonicNanos.getAsLong());
        if (currentLock.locked()) {
            rateLimited(response, currentLock.retryAfterSeconds());
            return;
        }

        Optional<WebDavApplicationCredentialService.CredentialPrincipal> authenticated =
                credentials.authenticate(supplied.username(), supplied.password());
        if (authenticated.isEmpty()) {
            int failureCount =
                    recordFailure(failureKey, monotonicNanos.getAsLong());
            if (failureCount >= MAX_FAILURES) {
                log.warn(
                        "WEBDAV_APPLICATION_CREDENTIAL_LOCKED failureKeyDigest={} attempts={}",
                        failureKey,
                        failureCount);
            }
            unauthorized(response);
            return;
        }

        clearFailures(failureKey);
        var principal = authenticated.orElseThrow();
        if (!allowedForMethod(principal, request.getMethod())) {
            response.sendError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "The WebDAV application credential does not permit this operation");
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext applicationContext =
                SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal.username(),
                        "[PROTECTED]",
                        principal.authorities());
        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));
        applicationContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(applicationContext);
        try {
            log.debug(
                    "WebDAV application credential accepted id={} user={} method={}",
                    principal.credentialId(),
                    principal.username(),
                    request.getMethod());
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static boolean allowedForMethod(
            WebDavApplicationCredentialService.CredentialPrincipal principal,
            String method) {
        String normalized =
                method == null ? "" : method.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "GET", "HEAD", "OPTIONS", "PROPFIND" ->
                    principal.readAllowed();
            case "PUT", "LOCK", "UNLOCK", "DELETE", "MKCOL", "MOVE", "COPY" ->
                    principal.writeAllowed();
            default -> false;
        };
    }

    static boolean isTemplateWebDavRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isBlank() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.equals("/dav/templates")
                || path.startsWith("/dav/templates/");
    }

    private static BasicParseResult parseBasic(String header) {
        if (header == null
                || !header.regionMatches(
                        true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return BasicParseResult.notBasic();
        }
        if (header.length() > MAX_AUTHORIZATION_HEADER_CHARS) {
            return BasicParseResult.invalid();
        }

        String encoded =
                header.substring(BASIC_PREFIX.length()).strip();
        if (encoded.isEmpty()) {
            return BasicParseResult.invalid();
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length > MAX_DECODED_CREDENTIAL_BYTES) {
                return BasicParseResult.invalid();
            }
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
            int separator = value.indexOf(':');
            if (separator <= 0) {
                return BasicParseResult.invalid();
            }

            String username = value.substring(0, separator);
            String password = value.substring(separator + 1);
            if (username.isBlank()
                    || codePointLength(username) > MAX_USERNAME_CODE_POINTS
                    || codePointLength(password) > MAX_PASSWORD_CODE_POINTS) {
                return BasicParseResult.invalid();
            }
            return BasicParseResult.valid(
                    new BasicCredential(username, password));
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            return BasicParseResult.invalid();
        }
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private LockState currentLock(String key, long now) {
        synchronized (stateMonitor) {
            cleanupExpired(now, false);
            FailureTracker tracker = failures.get(key);
            if (tracker == null && failures.size() >= maxTrackedFailureKeys) {
                cleanupExpired(now, true);
                if (failures.size() >= maxTrackedFailureKeys) {
                    tracker = overflowFailures;
                }
            }
            return tracker == null
                    ? LockState.unlocked()
                    : tracker.lockState(now);
        }
    }

    private int recordFailure(String key, long now) {
        synchronized (stateMonitor) {
            cleanupExpired(now, false);
            FailureTracker tracker = failures.get(key);
            if (tracker == null) {
                if (failures.size() >= maxTrackedFailureKeys) {
                    cleanupExpired(now, true);
                }
                if (failures.size() >= maxTrackedFailureKeys) {
                    tracker = overflowFailures;
                } else {
                    tracker = new FailureTracker();
                    failures.put(key, tracker);
                }
            }
            return tracker.recordFailure(now);
        }
    }

    private void clearFailures(String key) {
        synchronized (stateMonitor) {
            // An overflow identity has no individual entry. Never reset the
            // shared fail-closed bucket on one successful authentication.
            failures.remove(key);
        }
    }

    private void cleanupExpired(long now, boolean force) {
        if (!force && now - nextCleanupAt < 0) {
            return;
        }
        failures.entrySet().removeIf(
                entry -> entry.getValue().purgeAndIsEmpty(now));
        overflowFailures.purge(now);
        nextCleanupAt = now + CLEANUP_INTERVAL_NANOS;
    }

    int trackedFailureKeyCount() {
        synchronized (stateMonitor) {
            return failures.size();
        }
    }

    int overflowFailureCount() {
        synchronized (stateMonitor) {
            return overflowFailures.failureCount();
        }
    }

    private static String failureKey(
            HttpServletRequest request,
            String username) {
        String peer = Objects.toString(request.getRemoteAddr(), "");
        String normalizedUsername = Normalizer.normalize(
                        username.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(
                    "taxonomy-webdav-credential-lockout"
                            .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(peer.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(
                    normalizedUsername.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", impossible);
        }
    }

    private static void rateLimited(
            HttpServletResponse response,
            long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader(
                HttpHeaders.RETRY_AFTER,
                Long.toString(retryAfterSeconds));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"error\":\"TOO_MANY_WEBDAV_AUTHENTICATION_ATTEMPTS\","
                        + "\"status\":429,"
                        + "\"retryAfterSeconds\":"
                        + retryAfterSeconds
                        + "}");
    }

    private static void unauthorized(
            HttpServletResponse response) throws IOException {
        response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                "Basic realm=\"Taxonomy WebDAV\", charset=\"UTF-8\"");
        response.sendError(
                HttpServletResponse.SC_UNAUTHORIZED,
                "A valid WebDAV application credential is required");
    }

    private record BasicCredential(String username, String password) {
    }

    private record BasicParseResult(
            boolean basicScheme,
            BasicCredential credential) {

        private static BasicParseResult notBasic() {
            return new BasicParseResult(false, null);
        }

        private static BasicParseResult invalid() {
            return new BasicParseResult(true, null);
        }

        private static BasicParseResult valid(BasicCredential credential) {
            return new BasicParseResult(true, credential);
        }
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

        private final Deque<Long> attempts = new ArrayDeque<>(MAX_FAILURES);

        private int recordFailure(long now) {
            purge(now);
            if (attempts.size() < MAX_FAILURES) {
                attempts.addLast(now);
            }
            return attempts.size();
        }

        private LockState lockState(long now) {
            purge(now);
            if (attempts.size() < MAX_FAILURES) {
                return LockState.unlocked();
            }
            long elapsed = now - attempts.peekFirst();
            long remaining =
                    Math.max(1L, FAILURE_WINDOW_NANOS - elapsed);
            long retryAfterSeconds =
                    Math.max(
                            1L,
                            (remaining + NANOS_PER_SECOND - 1L)
                                    / NANOS_PER_SECOND);
            return new LockState(
                    true, retryAfterSeconds, attempts.size());
        }

        private boolean purgeAndIsEmpty(long now) {
            purge(now);
            return attempts.isEmpty();
        }

        private void purge(long now) {
            while (!attempts.isEmpty()
                    && now - attempts.peekFirst()
                            >= FAILURE_WINDOW_NANOS) {
                attempts.removeFirst();
            }
        }

        private int failureCount() {
            return attempts.size();
        }
    }
}
