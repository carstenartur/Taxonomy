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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticates revocable WebDAV app credentials without exposing account passwords. */
public final class WebDavApplicationCredentialFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(WebDavApplicationCredentialFilter.class);
    private static final String BASIC_PREFIX = "Basic ";
    private static final int MAX_FAILURES = 10;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_TRACKED_FAILURE_KEYS = 10_000;

    private final WebDavApplicationCredentialService credentials;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public WebDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials) {
        this(credentials, Clock.systemUTC());
    }

    WebDavApplicationCredentialFilter(
            WebDavApplicationCredentialService credentials,
            Clock clock) {
        this.credentials = credentials;
        this.clock = clock;
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
        if (!WebDavApplicationCredentialService.isApplicationSecret(supplied.password())) {
            chain.doFilter(request, response);
            return;
        }

        String failureKey = failureKey(request, supplied.username());
        if (isRateLimited(failureKey)) {
            response.setHeader("Retry-After", Long.toString(FAILURE_WINDOW.toSeconds()));
            response.sendError(429, "Too many failed WebDAV authentication attempts");
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
        Instant cutoff = clock.instant().minus(FAILURE_WINDOW);
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            purge(attempts, cutoff);
            return attempts.size() >= MAX_FAILURES;
        }
    }

    private void recordFailure(String key) {
        if (failures.size() >= MAX_TRACKED_FAILURE_KEYS) {
            purgeGlobal();
            if (failures.size() >= MAX_TRACKED_FAILURE_KEYS) {
                failures.clear();
            }
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key,
                ignored -> new ArrayDeque<>());
        synchronized (attempts) {
            purge(attempts, clock.instant().minus(FAILURE_WINDOW));
            attempts.addLast(clock.instant());
        }
    }

    private void clearFailures(String key) {
        failures.remove(key);
    }

    private void purgeGlobal() {
        Instant cutoff = clock.instant().minus(FAILURE_WINDOW);
        failures.entrySet().removeIf(entry -> {
            Deque<Instant> attempts = entry.getValue();
            synchronized (attempts) {
                purge(attempts, cutoff);
                return attempts.isEmpty();
            }
        });
    }

    private static void purge(Deque<Instant> attempts, Instant cutoff) {
        while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
            attempts.removeFirst();
        }
    }

    private static String failureKey(HttpServletRequest request, String username) {
        return String.valueOf(request.getRemoteAddr()) + "|" + username;
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Basic realm=\"Taxonomy WebDAV\", charset=\"UTF-8\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                "A valid WebDAV application credential is required");
    }

    private record BasicCredential(String username, String password) {
    }
}
