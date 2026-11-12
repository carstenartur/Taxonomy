package com.taxonomy.workspace.service;

import com.taxonomy.identity.StableIdentityHash;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
                       String branch, String sourceBranch, String requestedCommit, String expectedTrackedCommit,
                       String observedSourceCommit, String resultCommit, int attempts, String decisionCode,
                       long createdAt, Long lastAttemptAt, Long completedAt) { }
    record Claim(String operationId, String lease) { }
    @FunctionalInterface interface FetchStep {
        WorkspaceUpstreamService.Status apply(WorkspaceFetchOperation operation, Runnable checkLease) throws IOException;
    }

    public View admit(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant, WorkspaceUpstreamService.Status source,
                      WorkspaceUpstreamService.Request request, String key, String cause) {
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
                select f.id from WorkspaceFetchOperation f where f.nextAttemptAt <= :now
                order by f.nextAttemptAt, f.id""", String.class).setParameter("now", clock.millis())
                .setMaxResults(8).getResultList());
    }

    Claim claim(String id, String executor, boolean scheduled) {
        return transaction.execute(status -> {
            var row = entities.find(WorkspaceFetchOperation.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (row == null || (row.nextAttemptAt == null && (scheduled || !"AWAITING_AUTHORIZATION".equals(row.phase)))) return null;
            long now = clock.millis();
            if (row.nextAttemptAt != null && row.nextAttemptAt > now && (scheduled || "RUNNING".equals(row.phase))) return null;
            if (row.attempts >= MAX_ATTEMPTS) {
                finish(row, "MANUAL_REVIEW", "ATTEMPTS_EXHAUSTED", now);
                return null;
            }
            row.phase = "RUNNING";
            row.attempts++;
            row.executor = executor;
            row.lastAttemptAt = now;
            row.nextAttemptAt = now + LEASE_MILLIS;
            row.lease = UUID.randomUUID().toString();
            return new Claim(id, row.lease);
        });
    }

    View run(Claim claim, FetchStep fetch) throws IOException {
        try {
            return transaction.execute(status -> {
                var row = entities.find(WorkspaceFetchOperation.class, claim.operationId(), LockModeType.PESSIMISTIC_WRITE);
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
            var row = entities.find(WorkspaceFetchOperation.class, claim.operationId(), LockModeType.PESSIMISTIC_WRITE);
            if (row == null || !claim.lease().equals(row.lease)) return;
            long now = clock.millis();
            if (failure instanceof WorkspaceFetchAuthority.FreshLoginRequired) {
                row.phase = "AWAITING_AUTHORIZATION";
                row.decision = "FRESH_LOGIN_REQUIRED";
                row.nextAttemptAt = null;
                row.lease = null;
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
        if (!row.fingerprint.equals(command)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch command changed");
        return view(row);
    }

    private static View view(WorkspaceFetchOperation row) {
        return new View(row.id, row.causeId, row.phase, row.repositoryId, row.workspaceId, row.branch, row.sourceBranch,
                row.requested, row.expected, row.observed, row.result, row.attempts, row.decision,
                row.createdAt, row.lastAttemptAt, row.completedAt);
    }

    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Fetch operation unavailable"); }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
