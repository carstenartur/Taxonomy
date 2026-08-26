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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Authenticates revocable WebDAV app credentials without exposing account passwords. */
public final class WebDavApplicationCredentialFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(WebDavApplicationCredentialFilter.class);
    private static final String BASIC_PREFIX = "Basic ";
    private static final String OVERFLOW_BUCKET = "overflow";
    private static final int MAX_FAILURES = 10;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_FAILURE_KEYS = 10_000;
    private static final int MAX_TRACKED_IDENTITY_KEYS = MAX_TRACKED_FAILURE_KEYS - 1;
    private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 1024;
    private static final int MAX_USERNAME_LENGTH = 256;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final long FAILURE_WINDOW_NANOS = FAILURE_WINDOW.toNanos();
    private static final long PURGE_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
    private static final ThreadLocal<MessageDigest> SHA_256 =
            ThreadLocal.withInitial(WebDavApplicationCredentialFilter::newSha256);

    private final WebDavApplicationCredentialService credentials;
    private final LongSupplier nanoTime;
    private final Object failureLock = new Object();
    private final Map<String, Deque<Long>> failures = new HashMap<>();
    private long lastGlobalPurgeNanos = Long.MIN_VALUE;

    public WebDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials) {
        this(credentials, System::nanoTime);
    }

    WebDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials,
            LongSupplier nanoTime) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
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
        if (authorization != null && authorization.length() > MAX_AUTHORIZATION_HEADER_LENGTH) {
            unauthorized(response);
            return;
        }
        Optional<BasicCredential> basic = parseBasic(authorization);

        if (basic.isEmpty()) {
            if (authorization == null
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                unauthorized(response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        BasicCredential supplied = basic.orElseThrow();
        if (supplied.username().length() > MAX_USERNAME_LENGTH
                || supplied.password().length() > MAX_PASSWORD_LENGTH) {
            if (WebDavApplicationCredentialService.isApplicationSecret(supplied.password())) {
                failedApplicationCredential(request, supplied.username(), response);
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        if (!WebDavApplicationCredentialService.isApplicationSecret(supplied.password())) {
            chain.doFilter(request, response);
            return;
        }
        if (!WebDavApplicationCredentialService.hasExactApplicationSecretSyntax(
                supplied.password())) {
            failedApplicationCredential(request, supplied.username(), response);
            return;
        }

        String failureKey = failureKey(request, supplied.username());
        if (isRateLimited(failureKey)) {
            tooManyRequests(response);
            return;
        }

        Optional<WebDavApplicationCredentialService.CredentialPrincipal> authenticated =
                credentials.authenticate(supplied.username(), supplied.password());
        if (authenticated.isEmpty()) {
            recordFailure(failureKey);
            unauthorized(response);
            return;
        }
        clearFailures(failureKey);
        var principal = authenticated.orElseThrow();
        if (!allowedForMethod(principal, request.getMethod())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "The WebDAV application credential does not permit this operation");
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext applicationContext = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal.username(), "[PROTECTED]", principal.authorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        applicationContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(applicationContext);
        try {
            log.debug("WebDAV application credential accepted id={} user={} method={}",
                    principal.credentialId(), principal.username(), request.getMethod());
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void failedApplicationCredential(
            HttpServletRequest request,
            String username,
            HttpServletResponse response) throws IOException {
        String failureKey = failureKey(request, username);
        if (isRateLimited(failureKey)) {
            tooManyRequests(response);
            return;
        }
        recordFailure(failureKey);
        unauthorized(response);
    }

    private static boolean allowedForMethod(
            WebDavApplicationCredentialService.CredentialPrincipal principal,
            String method) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "GET", "HEAD", "OPTIONS", "PROPFIND" -> principal.readAllowed();
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
        return path.equals("/dav/templates") || path.startsWith("/dav/templates/");
    }

    private static Optional<BasicCredential> parseBasic(String header) {
        if (header == null || !header.regionMatches(true, 0,
                BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(
                    header.substring(BASIC_PREFIX.length()).strip());
            String value = new String(decoded, StandardCharsets.UTF_8);
            int separator = value.indexOf(':');
            if (separator <= 0) {
                return Optional.empty();
            }
            return Optional.of(new BasicCredential(
                    value.substring(0, separator),
                    value.substring(separator + 1)));
        } catch (IllegalArgumentException invalidBase64) {
            return Optional.empty();
        }
    }

    private boolean isRateLimited(String key) {
        synchronized (failureLock) {
            long now = nanoTime.getAsLong();
            purgeGlobalIfDue(now);
            Deque<Long> attempts = failures.get(rateLimitBucket(key));
            if (attempts == null) {
                return false;
            }
            purge(attempts, now - FAILURE_WINDOW_NANOS);
            return attempts.size() >= MAX_FAILURES;
        }
    }

    private void recordFailure(String key) {
        synchronized (failureLock) {
            long now = nanoTime.getAsLong();
            purgeGlobalIfDue(now);
            String bucket = admissionBucket(key);
            Deque<Long> attempts = failures.computeIfAbsent(bucket,
                    ignored -> new ArrayDeque<>());
            purge(attempts, now - FAILURE_WINDOW_NANOS);
            attempts.addLast(now);
        }
    }

    private void clearFailures(String key) {
        synchronized (failureLock) {
            failures.remove(key);
        }
    }

    private void purgeGlobal(long now) {
        long cutoff = now - FAILURE_WINDOW_NANOS;
        failures.entrySet().removeIf(entry -> {
            Deque<Long> attempts = entry.getValue();
            purge(attempts, cutoff);
            return attempts.isEmpty();
        });
    }

    private void purgeGlobalIfDue(long now) {
        if (lastGlobalPurgeNanos != Long.MIN_VALUE
                && now - lastGlobalPurgeNanos < PURGE_INTERVAL_NANOS) {
            return;
        }
        purgeGlobal(now);
        lastGlobalPurgeNanos = now;
    }

    private String admissionBucket(String key) {
        if (failures.containsKey(key)) {
            return key;
        }
        return regularKeyCount() < MAX_TRACKED_IDENTITY_KEYS ? key : OVERFLOW_BUCKET;
    }

    private String rateLimitBucket(String key) {
        if (failures.containsKey(key)) {
            return key;
        }
        return regularKeyCount() >= MAX_TRACKED_IDENTITY_KEYS ? OVERFLOW_BUCKET : key;
    }

    private int regularKeyCount() {
        return failures.size() - (failures.containsKey(OVERFLOW_BUCKET) ? 1 : 0);
    }

    private static void purge(Deque<Long> attempts, long cutoff) {
        while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) {
            attempts.removeFirst();
        }
    }

    private static String failureKey(HttpServletRequest request, String username) {
        return digest(String.valueOf(request.getRemoteAddr()))
                + ":" + digest(normalizeUsername(username));
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private static String digest(String value) {
        MessageDigest sha256 = SHA_256.get();
        sha256.reset();
        byte[] hash = sha256.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder encoded = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            encoded.append(Character.forDigit((b >>> 4) & 0xF, 16));
            encoded.append(Character.forDigit(b & 0xF, 16));
        }
        return encoded.toString();
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            throw new IllegalStateException("SHA-256 is not available", missingAlgorithm);
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Basic realm=\"Taxonomy WebDAV\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "A valid WebDAV application credential is required");
    }

    private static void tooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(FAILURE_WINDOW.toSeconds()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write(
                "{\"error\":\"too_many_failed_webdav_authentication_attempts\"}");
    }

    int trackedFailureKeyCount() {
        synchronized (failureLock) {
            return failures.size();
        }
    }

    private record BasicCredential(String username, String password) {
    }
}
