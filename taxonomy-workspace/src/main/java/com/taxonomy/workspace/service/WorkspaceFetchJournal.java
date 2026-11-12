package com.taxonomy.workspace.service;

import com.taxonomy.identity.StableIdentityHash;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Admission, claim and Git publication are independently committed and fenced by the same operation row. */
@Service
public class WorkspaceFetchJournal {
    static final long LEASE_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_ATTEMPTS = 6;
    static final long RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    static final long ARCHIVE_MILLIS = Duration.ofDays(90).toMillis();
    private final EntityManager entities;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public WorkspaceFetchJournal(EntityManager entities, PlatformTransactionManager transactions) {
        this(entities, transactions, Clock.systemUTC());
    }

    WorkspaceFetchJournal(EntityManager entities, PlatformTransactionManager transactions, Clock clock) {
        this.entities = entities;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public record View(String operationId, String causationId, String phase, String repositoryId, String workspaceId,
                       String branch, String sourceBranch, String originalForkPoint, String requestedCommit, String expectedTrackedCommit,
                       String observedSourceCommit, String resultCommit, int attempts, String decisionCode,
                       long createdAt, Long lastAttemptAt, Long completedAt, String originalActor, String lastExecutor,
                       String authorizationKind, String requiredPolicy, String outcome, Long archivedAt, String archivedBy) { }
    record Claim(String operationId, String lease) { }
    @FunctionalInterface interface FetchStep {
        WorkspaceUpstreamService.Status apply(WorkspaceFetchOperation operation, Runnable checkLease) throws IOException;
    }

    public View admit(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant, WorkspaceUpstreamService.Capture capture,
                      WorkspaceUpstreamService.Request request, String key, String cause) {
        var source = capture.source();
        String actor = grant.principalId();
        if (key != null && !key.matches("[a-zA-Z0-9._~:-]{1,128}")) throw badRequest("Invalid fetch idempotency key");
        if (cause != null && !cause.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw badRequest("Invalid fetch causation ID");
        }
        String command = StableIdentityHash.sha256(String.join("\u0000", "fetch", source.sourceBranch(), request.sourceCommit(),
                Objects.toString(request.expectedTrackedCommit(), "")));
        String id = StableIdentityHash.sha256(String.join("\u0000", selected.repositoryWorkspaceScopeKey(), actor,
                key == null ? "derived:" + command : "provided:" + key));
        try {
            return transaction.execute(status -> {
                var existing = entities.find(WorkspaceFetchOperation.class, id);
                if (existing != null) return replay(existing, selected, actor, command);
                var operation = new WorkspaceFetchOperation();
                operation.id = id;
                operation.fingerprint = command;
                operation.causeId = cause == null ? id : UUID.fromString(cause).toString();
                operation.actor = actor;
                operation.loginKind = grant.loginKind();
                operation.bindingKey = grant.bindingKey();
                operation.policy = "ANALYSIS_RUN+CONTRIBUTOR";
                operation.username = selected.username();
                operation.repositoryId = selected.repositoryId();
                operation.workspaceId = selected.workspaceId();
                operation.branch = selected.branch();
                operation.sourceBranch = source.sourceBranch();
                operation.forkPoint = capture.forkPoint();
                operation.requested = request.sourceCommit();
                operation.expected = request.expectedTrackedCommit();
                operation.observed = source.sourceCommit();
                operation.phase = "ADMITTED";
                operation.createdAt = clock.millis();
                operation.nextAttemptAt = operation.createdAt;
                entities.persist(operation);
                entities.flush();
                return view(operation);
            });
        } catch (RuntimeException failure) {
            // An independent request may have committed the same scoped primary key first.
            var existing = transaction.execute(status -> entities.find(WorkspaceFetchOperation.class, id));
            if (existing == null) throw failure;
            return replay(existing, selected, actor, command);
        }
    }

    public View status(RepositoryContext selected, String principal, String id) {
        return transaction.execute(status -> {
            var operation = entities.find(WorkspaceFetchOperation.class, id);
            if (operation == null || !operation.ownedBy(selected, principal)) throw missing();
            return view(operation);
        });
    }

    List<String> due() {
        return transaction.execute(status -> entities.createQuery("""
                select f.id from WorkspaceFetchOperation f where f.phase in ('ADMITTED','RUNNING','RETRYABLE') and f.nextAttemptAt <= :now
                order by f.nextAttemptAt, f.id""", String.class).setParameter("now", clock.millis())
                .setMaxResults(8).getResultList());
    }

    public View archive(RepositoryContext scope, String principal, String id) {
        return transaction.execute(status -> {
            var row = entities.find(WorkspaceFetchOperation.class, id);
            if (row == null || !row.ownedBy(scope, principal)) throw missing();
            if (row.completedAt == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "A live fetch cannot be archived");
            archiveSettled(id, principal, clock.millis(), Long.MAX_VALUE);
            entities.refresh(row);
            return view(row);
        });
    }

    /** Archive settled data after 30 days. Retain the immutable outcome and deletion decision for another 90 days. */
    void expireDue() {
        long now = clock.millis();
        var ids = transaction.execute(status -> entities.createQuery("""
                select f.id from WorkspaceFetchOperation f where f.archivedAt <= :purge
                or (f.archivedAt is null and (f.completedAt <= :archive or
                    (f.phase='AWAITING_AUTHORIZATION' and f.lastAttemptAt <= :archive))) order by f.id""", String.class)
                .setParameter("purge", now - ARCHIVE_MILLIS).setParameter("archive", now - RETENTION_MILLIS)
                .setMaxResults(16).getResultList());
        for (String id : ids) transaction.executeWithoutResult(status -> {
            int purged = entities.createQuery("""
                    delete from WorkspaceFetchOperation f where f.id=:id and f.archivedAt <= :cutoff""")
                    .setParameter("id", id).setParameter("cutoff", now - ARCHIVE_MILLIS).executeUpdate();
            if (purged == 1) return;
            String executor = "system:workspace-fetch-retention";
            int archived = archiveSettled(id, executor, now, now - RETENTION_MILLIS);
            if (archived == 1) return;
            entities.createQuery("""
                    update WorkspaceFetchOperation f set f.phase='MANUAL_REVIEW', f.decision='AUTHORIZATION_EXPIRED',
                    f.completedAt=:now, f.archivedAt=:now, f.archivedBy=:executor, f.observed=null, f.result=null
                    where f.id=:id and f.phase='AWAITING_AUTHORIZATION' and f.archivedAt is null and f.lastAttemptAt <= :cutoff""")
                    .setParameter("id", id).setParameter("executor", executor).setParameter("now", now)
                    .setParameter("cutoff", now - RETENTION_MILLIS).executeUpdate();
        });
    }

    private int archiveSettled(String id, String actor, long now, long cutoff) {
        return entities.createQuery("""
                update WorkspaceFetchOperation f set f.archivedAt=:now, f.archivedBy=:actor, f.observed=null, f.result=null
                where f.id=:id and f.archivedAt is null and f.completedAt <= :cutoff""")
                .setParameter("id", id).setParameter("actor", actor).setParameter("now", now)
                .setParameter("cutoff", cutoff).executeUpdate();
    }

    Claim claim(String id, String executor, boolean scheduled) {
        long now = clock.millis();
        String eligible = scheduled
                ? "(f.phase in ('ADMITTED','RUNNING','RETRYABLE') and f.nextAttemptAt <= :now)"
                : "(f.phase in ('ADMITTED','RETRYABLE','AWAITING_AUTHORIZATION') or (f.phase='RUNNING' and f.nextAttemptAt <= :now))";
        String lease = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            int changed = entities.createQuery("""
                    update WorkspaceFetchOperation f set f.phase='RUNNING', f.attempts=f.attempts+1,
                    f.executor=:executor, f.lastAttemptAt=:now, f.nextAttemptAt=:until, f.lease=:lease
                    where f.id=:id and f.archivedAt is null and f.attempts < :limit and %s""".formatted(eligible))
                    .setParameter("id", id).setParameter("executor", executor).setParameter("lease", lease)
                    .setParameter("now", now).setParameter("until", now + LEASE_MILLIS)
                    .setParameter("limit", MAX_ATTEMPTS).executeUpdate();
            if (changed == 1) return new Claim(id, lease);
            entities.createQuery("""
                    update WorkspaceFetchOperation f set f.phase='MANUAL_REVIEW', f.decision='ATTEMPTS_EXHAUSTED',
                    f.completedAt=:now, f.nextAttemptAt=null, f.lease=null
                    where f.id=:id and f.archivedAt is null and f.attempts >= :limit and %s""".formatted(eligible))
                    .setParameter("id", id).setParameter("now", now).setParameter("limit", MAX_ATTEMPTS).executeUpdate();
            return null;
        });
    }

    /** The conditional write takes the physical row lock before any Git callback can see the lease. */
    private WorkspaceFetchOperation lockedClaim(Claim claim) {
        int locked = entities.createQuery("""
                update WorkspaceFetchOperation f set f.lease=:lease
                where f.id=:id and f.phase='RUNNING' and f.lease=:lease""")
                .setParameter("id", claim.operationId()).setParameter("lease", claim.lease()).executeUpdate();
        return locked == 1 ? entities.find(WorkspaceFetchOperation.class, claim.operationId()) : null;
    }

    View run(Claim claim, FetchStep fetch) throws IOException {
        try {
            return transaction.execute(status -> {
                var row = lockedClaim(claim);
                requireLease(row, claim);
                try {
                    var result = fetch.apply(row, () -> requireLease(row, claim));
                    row.observed = result.sourceCommit();
                    row.result = result.trackedCommit();
                    finish(row, "COMPLETED", null, clock.millis());
                    return view(row);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        } catch (RuntimeException failure) {
            failed(claim, failure);
            if (failure instanceof UncheckedIOException io) throw io.getCause();
            throw failure;
        }
    }

    private void requireLease(WorkspaceFetchOperation row, Claim claim) {
        if (row == null || !claim.lease().equals(row.lease) || !"RUNNING".equals(row.phase)
                || row.nextAttemptAt == null || row.nextAttemptAt <= clock.millis()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch ownership changed");
        }
    }

    private void failed(Claim claim, RuntimeException failure) {
        transaction.executeWithoutResult(status -> {
            var row = lockedClaim(claim);
            if (row == null || !claim.lease().equals(row.lease)) return;
            long now = clock.millis();
            if (failure instanceof WorkspaceFetchAuthority.FreshLoginRequired) {
                row.phase = "AWAITING_AUTHORIZATION";
                row.decision = "FRESH_LOGIN_REQUIRED";
                row.nextAttemptAt = null;
                row.lease = null;
            } else if (failure instanceof UncheckedIOException io && io.getCause() instanceof GitUpstreamTransfer.Superseded superseded) {
                row.result = superseded.retainedCommit;
                finish(row, "SUPERSEDED", "RETAINED_DESCENDANT", now);
            } else if (failure instanceof AccessDeniedException) finish(row, "DENIED", "AUTHORITY_CHANGED", now);
            else if (failure instanceof UncheckedIOException io && io.getCause() instanceof BranchHeadConflictException) finish(row, "MANUAL_REVIEW", "GIT_AUTHORITY_CHANGED", now);
            else if (row.attempts >= MAX_ATTEMPTS) finish(row, "MANUAL_REVIEW", "ATTEMPTS_EXHAUSTED", now);
            else {
                row.phase = "RETRYABLE";
                row.decision = "FETCH_INTERRUPTED";
                row.lease = null;
                row.nextAttemptAt = now + Duration.ofSeconds(15L << (row.attempts - 1)).toMillis();
            }
        });
    }

    private static void finish(WorkspaceFetchOperation row, String phase, String decision, long now) {
        row.phase = phase;
        row.decision = decision;
        row.completedAt = now;
        row.nextAttemptAt = null;
        row.lease = null;
    }

    private static View replay(WorkspaceFetchOperation row, RepositoryContext selected, String principal, String command) {
        if (!row.ownedBy(selected, principal)) throw missing();
        if (row.archivedAt != null) throw new ResponseStatusException(HttpStatus.GONE, "FETCH_ARCHIVED");
        if (!row.fingerprint.equals(command)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch command changed");
        return view(row);
    }

    private static View view(WorkspaceFetchOperation row) {
        return new View(row.id, row.causeId, row.archivedAt == null ? row.phase : "ARCHIVED", row.repositoryId, row.workspaceId, row.branch, row.sourceBranch,
                row.forkPoint, row.requested, row.expected, row.observed, row.result, row.attempts, row.decision,
                row.createdAt, row.lastAttemptAt, row.completedAt, row.actor, row.executor, row.loginKind,
                row.policy, row.completedAt == null ? null : row.phase, row.archivedAt, row.archivedBy);
    }

    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Fetch operation unavailable"); }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
