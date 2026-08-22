package com.taxonomy.templates;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded in-process WebDAV edit locks.
 *
 * <p>Locks improve the Word editing experience; Git per-template expected-version checks
 * remain the durable lost-update protection if a process restarts and its locks expire.</p>
 */
@Component
public class DocumentTemplateWebDavLockManager {

    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);
    static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    private final Map<String, TemplateLock> locks = new HashMap<>();
    private final Clock clock;

    public DocumentTemplateWebDavLockManager() {
        this(Clock.systemUTC());
    }

    DocumentTemplateWebDavLockManager(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized TemplateLock acquire(
            String resource,
            String owner,
            String currentCommit,
            Duration requestedTimeout,
            String refreshToken) {

        purgeExpired();
        TemplateLock existing = locks.get(resource);
        Duration timeout = boundedTimeout(requestedTimeout);
        Instant expiresAt = clock.instant().plus(timeout);

        if (existing != null) {
            if (refreshToken != null
                    && existing.token().equals(normalizeToken(refreshToken))
                    && existing.owner().equals(owner)) {
                TemplateLock refreshed = new TemplateLock(
                        existing.resource(),
                        existing.token(),
                        existing.owner(),
                        existing.baseCommit(),
                        existing.currentCommit(),
                        expiresAt);
                locks.put(resource, refreshed);
                return refreshed;
            }
            throw new LockConflictException(
                    "Template is already locked by another edit session");
        }

        TemplateLock created = new TemplateLock(
                resource,
                "opaquelocktoken:" + UUID.randomUUID(),
                owner,
                currentCommit,
                currentCommit,
                expiresAt);
        locks.put(resource, created);
        return created;
    }

    public synchronized TemplateLock require(
            String resource,
            String token,
            String owner) {
        purgeExpired();
        return requireExisting(resource, token, owner);
    }

    public synchronized TemplateLock find(String resource) {
        purgeExpired();
        return locks.get(resource);
    }

    /**
     * Run one versioned write and advance its lock without a post-commit failure window.
     *
     * <p>The manager monitor is deliberately held across the bounded template validation
     * and Git commit. This prevents a competing lock acquisition during an unlocked
     * conditional write and prevents an expired lease from turning a successful Git commit
     * into a WebDAV 423 response. Template edits are rare administrative operations, so the
     * stronger correctness boundary is preferable to parallel lock mutations.</p>
     */
    public synchronized <T> T executeWrite(
            String resource,
            String token,
            String owner,
            String expectedWithoutLock,
            LockedWrite<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        purgeExpired();

        TemplateLock active = locks.get(resource);
        String expected = expectedWithoutLock;
        if (active != null) {
            active = requireExisting(resource, token, owner);
            Instant protectedUntil = later(
                    active.expiresAt(), clock.instant().plus(MAX_TIMEOUT));
            active = new TemplateLock(
                    active.resource(),
                    active.token(),
                    active.owner(),
                    active.baseCommit(),
                    active.currentCommit(),
                    protectedUntil);
            locks.put(resource, active);
            expected = active.currentCommit();
        } else if (token != null && !token.isBlank()) {
            throw new LockConflictException(
                    "The supplied WebDAV lock token is no longer active");
        }

        LockedWriteResult<T> result = Objects.requireNonNull(
                operation.execute(expected), "locked write result");
        if (active != null) {
            Instant refreshedUntil = later(
                    active.expiresAt(), clock.instant().plus(DEFAULT_TIMEOUT));
            locks.put(resource, new TemplateLock(
                    active.resource(),
                    active.token(),
                    active.owner(),
                    active.baseCommit(),
                    Objects.requireNonNull(result.currentCommit(), "currentCommit"),
                    refreshedUntil));
        }
        return result.value();
    }

    public synchronized TemplateLock advance(
            String resource,
            String token,
            String owner,
            String commitId) {
        TemplateLock existing = require(resource, token, owner);
        TemplateLock advanced = new TemplateLock(
                existing.resource(),
                existing.token(),
                existing.owner(),
                existing.baseCommit(),
                commitId,
                existing.expiresAt());
        locks.put(resource, advanced);
        return advanced;
    }

    public synchronized void release(String resource, String token, String owner) {
        TemplateLock existing = require(resource, token, owner);
        locks.remove(existing.resource());
    }

    private TemplateLock requireExisting(
            String resource,
            String token,
            String owner) {
        TemplateLock existing = locks.get(resource);
        if (existing == null) {
            throw new LockConflictException(
                    "No active WebDAV lock exists for this template");
        }
        String normalized = normalizeToken(token);
        if (!existing.token().equals(normalized) || !existing.owner().equals(owner)) {
            throw new LockConflictException(
                    "WebDAV lock token or owner does not match");
        }
        return existing;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        locks.values().removeIf(lock -> !lock.expiresAt().isAfter(now));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Duration boundedTimeout(Duration requested) {
        if (requested == null || requested.isNegative() || requested.isZero()) {
            return DEFAULT_TIMEOUT;
        }
        return requested.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : requested;
    }

    static String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String normalized = token.strip();
        if (normalized.startsWith("<") && normalized.endsWith(">")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    @FunctionalInterface
    public interface LockedWrite<T> {
        LockedWriteResult<T> execute(String expectedCommit) throws IOException;
    }

    public record LockedWriteResult<T>(T value, String currentCommit) {
    }

    public record TemplateLock(
            String resource,
            String token,
            String owner,
            String baseCommit,
            String currentCommit,
            Instant expiresAt) {
    }

    public static final class LockConflictException extends RuntimeException {
        LockConflictException(String message) {
            super(message);
        }
    }
}
