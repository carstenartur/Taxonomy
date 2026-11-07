package com.taxonomy.templates;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Shared WebDAV leases; Git expected-version checks remain the durable lost-update boundary. */
@Component
public class DocumentTemplateWebDavLockManager {
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);
    static final Duration MAX_TIMEOUT = Duration.ofHours(1);
    private final DocumentTemplateWebDavLockStore store;

    /** Production composition never falls back to process-local locks. */
    @Autowired
    public DocumentTemplateWebDavLockManager(DataSource database) {
        store = new JdbcDocumentTemplateWebDavLockStore(database);
    }

    /** Explicit standalone protocol/unit fixtures use their own in-memory authority. */
    public DocumentTemplateWebDavLockManager() { this(Clock.systemUTC()); }

    DocumentTemplateWebDavLockManager(Clock clock) {
        store = new InMemoryLockStore(Objects.requireNonNull(clock, "clock"));
    }

    public TemplateLock acquire(String resource, String owner, String currentCommit,
                                Duration requestedTimeout, String refreshToken) {
        requireLockIdentity(resource, "resource");
        requireLockIdentity(owner, "owner");
        return operation(resource, lease -> {
            TemplateLock existing = lease.current();
            Instant expiresAt = lease.now().plus(boundedTimeout(requestedTimeout));
            if (existing != null) {
                if (refreshToken != null && existing.token().equals(normalizeToken(refreshToken))
                        && existing.owner().equals(owner)) {
                    var refreshed = new TemplateLock(existing.resource(), existing.token(), existing.owner(),
                            existing.baseCommit(), existing.currentCommit(), expiresAt);
                    lease.save(refreshed);
                    return refreshed;
                }
                throw new LockConflictException("Template is already locked by another edit session");
            }
            if (refreshToken != null && !refreshToken.isBlank()) {
                throw new LockConflictException("The supplied WebDAV lock token is no longer active");
            }
            var created = new TemplateLock(resource, "opaquelocktoken:" + UUID.randomUUID(),
                    owner, currentCommit, currentCommit, expiresAt);
            lease.save(created);
            return created;
        });
    }

    public TemplateLock require(String resource, String token, String owner) {
        return operation(resource, lease -> requireExisting(lease.current(), token, owner));
    }

    public TemplateLock find(String resource) {
        return operation(resource, DocumentTemplateWebDavLockStore.Lease::current);
    }

    /** Hold the shared fence through validation and the durable Git commit. */
    public <T> T executeWrite(String resource, String token, String owner,
                             String expectedWithoutLock, LockedWrite<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        return store.locked(resource, lease -> {
            TemplateLock active = lease.current();
            String expected = expectedWithoutLock;
            if (active != null) {
                active = requireExisting(active, token, owner);
                expected = active.currentCommit();
            } else if (token != null && !token.isBlank()) {
                throw new LockConflictException("The supplied WebDAV lock token is no longer active");
            }
            LockedWriteResult<T> result = Objects.requireNonNull(
                    operation.execute(expected), "locked write result");
            if (active != null) {
                Instant refreshedUntil = later(active.expiresAt(), lease.now().plus(DEFAULT_TIMEOUT));
                lease.save(new TemplateLock(active.resource(), active.token(), active.owner(),
                        active.baseCommit(), Objects.requireNonNull(result.currentCommit(), "currentCommit"),
                        refreshedUntil));
            }
            return result.value();
        });
    }

    public TemplateLock advance(String resource, String token, String owner, String commitId) {
        return operation(resource, lease -> {
            var existing = requireExisting(lease.current(), token, owner);
            var advanced = new TemplateLock(existing.resource(), existing.token(), existing.owner(),
                    existing.baseCommit(), commitId, existing.expiresAt());
            lease.save(advanced);
            return advanced;
        });
    }

    public void release(String resource, String token, String owner) {
        operation(resource, lease -> {
            requireExisting(lease.current(), token, owner);
            lease.remove();
            return null;
        });
    }

    private <T> T operation(String resource, DocumentTemplateWebDavLockStore.Operation<T> operation) {
        try {
            return store.locked(resource, operation);
        } catch (IOException failure) {
            throw new LockStoreUnavailableException("Shared WebDAV edit authority is unavailable", failure);
        }
    }

    private static TemplateLock requireExisting(TemplateLock existing, String token, String owner) {
        if (existing == null) throw new LockConflictException("No active WebDAV lock exists for this template");
        if (!existing.token().equals(normalizeToken(token)) || !existing.owner().equals(owner)) {
            throw new LockConflictException("WebDAV lock token or owner does not match");
        }
        return existing;
    }

    private static void requireLockIdentity(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WebDAV lock " + label + " must not be blank");
        }
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Duration boundedTimeout(Duration requested) {
        if (requested == null || requested.isNegative() || requested.isZero()) return DEFAULT_TIMEOUT;
        return requested.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : requested;
    }

    static String normalizeToken(String token) {
        if (token == null) return null;
        String normalized = token.strip();
        if (normalized.startsWith("<") && normalized.endsWith(">")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    @FunctionalInterface
    public interface LockedWrite<T> { LockedWriteResult<T> execute(String expectedCommit) throws IOException; }
    public record LockedWriteResult<T>(T value, String currentCommit) { }
    public record TemplateLock(String resource, String token, String owner, String baseCommit,
                               String currentCommit, Instant expiresAt) { }

    public static final class LockConflictException extends RuntimeException {
        LockConflictException(String message) { super(message); }
    }

    public static final class LockStoreUnavailableException extends RuntimeException {
        LockStoreUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class InMemoryLockStore implements DocumentTemplateWebDavLockStore {
        private final Map<String, TemplateLock> locks = new HashMap<>();
        private final Clock clock;
        private InMemoryLockStore(Clock clock) { this.clock = clock; }

        @Override public synchronized <T> T locked(String resource, Operation<T> operation) throws IOException {
            locks.values().removeIf(lock -> !lock.expiresAt().isAfter(clock.instant()));
            return operation.execute(new Lease() {
                @Override public TemplateLock current() { return locks.get(resource); }
                @Override public Instant now() { return clock.instant(); }
                @Override public void save(TemplateLock lock) { locks.put(resource, lock); }
                @Override public void remove() { locks.remove(resource); }
            });
        }
    }
}
