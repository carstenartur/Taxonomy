package com.taxonomy.workspace.service;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.storage.GitUpstreamRef;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Admission, claim and Git publication are independently committed and fenced by the same operation row. */
@Service
public class WorkspaceFetchJournal {
    private enum Owner {
        WORKSPACE(WorkspaceFetchOperation.class), CENTRAL(CentralFetchOperation.class);

        final Class<? extends RepositoryFetchOperation> type;
        Owner(Class<? extends RepositoryFetchOperation> type) { this.type = type; }

        String query(String statement, Object... additional) {
            return statement.replace("{entity}", type.getSimpleName()).formatted(additional);
        }

        static Owner of(String id) {
            // Historical working-copy receipts are full SHA-256 hex IDs. Central IDs have a distinct prefix.
            return id.startsWith("f-") || CentralFetchOperation.initialIdFormat(id) ? CENTRAL : WORKSPACE;
        }
    }

    static final long LEASE_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_ATTEMPTS = 6;
    static final long RETENTION_MILLIS = Duration.ofDays(30).toMillis();
    static final long ARCHIVE_MILLIS = Duration.ofDays(90).toMillis();
    private final EntityManager entities;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final RepositoryOperationLeases leases;

    @Autowired
    public WorkspaceFetchJournal(EntityManager entities, PlatformTransactionManager transactions) {
        this(entities, transactions, Clock.systemUTC());
    }

    WorkspaceFetchJournal(EntityManager entities, PlatformTransactionManager transactions, Clock clock) {
        this.entities = entities;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.leases = new RepositoryOperationLeases(entities, transaction, clock, LEASE_MILLIS, MAX_ATTEMPTS);
    }

    public record View(String operationId, String causationId, String phase, String repositoryId, String workspaceId,
                       String branch, String sourceBranch, String originalForkPoint, String requestedCommit, String expectedTrackedCommit,
                       String observedSourceCommit, String resultCommit, int attempts, String decisionCode,
                       long createdAt, Long lastAttemptAt, Long completedAt, String originalActor, String lastExecutor,
                       String authorizationKind, String requiredPolicy, String outcome, Long archivedAt, String archivedBy,
                       String sourceRepositoryId, String trackingRef, String selectedTag, String requestedTagObject,
                       String resultTagObject) { }
    @FunctionalInterface interface FetchStep {
        WorkspaceUpstreamService.Status apply(RepositoryFetchOperation operation, Runnable checkLease) throws IOException;
    }

    /** Join the allocating catalogue transaction. A process cannot leave one side committed without the other. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void admitInitialFork(com.taxonomy.workspace.model.SystemRepository fork, WorkspaceFetchAuthority.Grant grant) {
        var operation = new CentralFetchOperation();
        operation.id = CentralFetchOperation.initialId(fork.getRepositoryId());
        operation.targetRepositoryId = fork.getRepositoryId();
        operation.sourceRepositoryId = fork.getUpstreamRepositoryId();
        operation.actor = grant.principalId();
        operation.loginKind = grant.loginKind();
        operation.bindingKey = grant.bindingKey();
        operation.username = fork.getOwnerId();
        operation.branch = fork.getDefaultBranch();
        operation.sourceBranch = fork.getUpstreamBranch();
        operation.requested = fork.getForkPointCommit();
        operation.forkPoint = operation.requested;
        operation.observed = operation.requested;
        operation.fingerprint = fork.forkRequestFingerprint();
        operation.causeId = operation.id;
        operation.policy = "ARCHITECTURE_WRITE+CONTRIBUTOR";
        operation.phase = "ADMITTED";
        operation.createdAt = clock.millis();
        operation.nextAttemptAt = operation.createdAt;
        entities.persist(operation);
    }

    boolean hasInitialFork(String targetId) {
        return transaction.execute(status -> find(CentralFetchOperation.initialId(targetId)) != null);
    }

    public View admit(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant, WorkspaceUpstreamService.Capture capture,
                      WorkspaceUpstreamService.Request request, String key, String cause) {
        return admit(selected, grant, capture, request, null, key, cause);
    }

    public View admitTag(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant, WorkspaceUpstreamService.Capture capture,
                         WorkspaceUpstreamService.TagRequest tag, String key, String cause) {
        return admit(selected, grant, capture, new WorkspaceUpstreamService.Request(tag.trackedCommit(), tag.trackedCommit()),
                tag, key, cause);
    }

    private View admit(RepositoryContext selected, WorkspaceFetchAuthority.Grant grant, WorkspaceUpstreamService.Capture capture,
                       WorkspaceUpstreamService.Request request, WorkspaceUpstreamService.TagRequest tag, String key, String cause) {
        var source = capture.source();
        String actor = grant.principalId();
        if (key != null && !key.matches("[a-zA-Z0-9._~:-]{1,128}")) throw badRequest("Invalid fetch idempotency key");
        if (cause != null && !cause.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw badRequest("Invalid fetch causation ID");
        }
        String commandFields = tag == null
                ? StableIdentityHash.sha256(String.join("\u0000", "fetch", source.sourceBranch(), request.sourceCommit(),
                        Objects.toString(request.expectedTrackedCommit(), "")))
                : StableIdentityHash.sha256(String.join("\u0000", "fetch-tag", source.sourceRepositoryId(), source.sourceBranch(),
                        tag.name(), tag.sourceTagObject(), tag.trackedCommit()));
        boolean central = selected.scope() == RepositoryScope.CENTRAL_WRITE;
        final String command;
        if (central) {
            if (!source.sourceBranch().equals(selected.branch())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The upstream binding changed before admission");
            }
            command = StableIdentityHash.sha256(String.join("\u0000", "central-fork", source.sourceRepositoryId(),
                    Objects.toString(capture.forkPoint(), ""), commandFields));
        } else command = commandFields;
        String scope = central ? String.join("\u0000", "central-fork", selected.repositoryId(), selected.branch())
                : selected.repositoryWorkspaceScopeKey();
        String digest = StableIdentityHash.sha256(String.join("\u0000", scope, actor,
                key == null ? "derived:" + command : "provided:" + key));
        String id = central ? "f-" + digest.substring(0, 62) : digest;
        try {
            return transaction.execute(status -> {
                var existing = find(id);
                if (existing != null) return replay(existing, selected, actor, command);
                RepositoryFetchOperation operation;
                if (central) {
                    var fork = new CentralFetchOperation();
                    fork.targetRepositoryId = selected.repositoryId();
                    fork.sourceRepositoryId = source.sourceRepositoryId();
                    operation = fork;
                } else {
                    var copy = new WorkspaceFetchOperation();
                    copy.repositoryId = selected.repositoryId();
                    copy.workspaceId = selected.workspaceId();
                    operation = copy;
                }
                operation.id = id;
                operation.fingerprint = command;
                operation.causeId = cause == null ? id : UUID.fromString(cause).toString();
                operation.actor = actor;
                operation.loginKind = grant.loginKind();
                operation.bindingKey = grant.bindingKey();
                operation.policy = central ? "ARCHITECTURE_WRITE+CONTRIBUTOR" : "ANALYSIS_RUN+CONTRIBUTOR";
                operation.username = selected.username();
                operation.selectedTag = tag == null ? null : tag.name();
                operation.requestedTagObject = tag == null ? null : tag.sourceTagObject();
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
            var existing = transaction.execute(status -> find(id));
            if (existing == null) throw failure;
            return replay(existing, selected, actor, command);
        }
    }

    public View status(RepositoryContext selected, String principal, String id) {
        return transaction.execute(status -> {
            var operation = find(id);
            if (operation == null || !operation.ownedBy(selected, principal)) throw missing();
            return view(operation);
        });
    }

    private RepositoryFetchOperation find(String id) { return entities.find(Owner.of(id).type, id); }

    List<String> due() {
        return transaction.execute(status -> {
            var due = new ArrayList<String>();
            for (Owner owner : Owner.values()) {
                due.addAll(entities.createQuery(owner.query("""
                        select f.id from {entity} f where f.phase in ('ADMITTED','RUNNING','RETRYABLE') and f.nextAttemptAt <= :now
                        order by f.nextAttemptAt, f.id"""), String.class).setParameter("now", clock.millis())
                        .setMaxResults(8).getResultList());
            }
            return due;
        });
    }

    /** Joins owner deletion, so failed Git storage cleanup rolls back receipt changes as well. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void archiveForWorkspaceRemoval(String workspace,String repository,String owner,java.util.function.Supplier<String> deletingActor) {
        var active = entities.createQuery("""
                select f.id from WorkspaceFetchOperation f where f.workspaceId=:workspace and f.repositoryId=:repository
                and f.username=:owner and f.archivedAt is null""",String.class)
                .setParameter("workspace",workspace).setParameter("repository",repository).setParameter("owner",owner)
                .setMaxResults(1).getResultList();
        if (active.isEmpty()) return;
        String actor=Objects.requireNonNull(deletingActor.get(),"Deleting workspace actor");
        entities.createQuery("""
                update WorkspaceFetchOperation f set
                    f.phase=case when f.completedAt is null then 'DENIED' else f.phase end,
                    f.decision=case when f.completedAt is null then 'WORKSPACE_DELETED' else f.decision end,
                    f.completedAt=coalesce(f.completedAt,:now), f.archivedAt=:now, f.archivedBy=:actor,
                    f.nextAttemptAt=null, f.lease=null, f.observed=null, f.result=null, f.resultTagObject=null
                where f.workspaceId=:workspace and f.repositoryId=:repository and f.username=:owner and f.archivedAt is null""")
                .setParameter("workspace",workspace).setParameter("repository",repository).setParameter("owner",owner)
                .setParameter("actor",actor).setParameter("now",clock.millis()).executeUpdate();
    }

    public View archive(RepositoryContext scope, String principal, String id) {
        return transaction.execute(status -> {
            var row = find(id);
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
        var ids = transaction.execute(status -> {
            var due = new ArrayList<String>();
            for (Owner owner : Owner.values()) {
                due.addAll(entities.createQuery(owner.query("""
                        select f.id from {entity} f where f.archivedAt <= :purge
                        or (f.archivedAt is null and (f.completedAt <= :archive or
                            (f.phase='AWAITING_AUTHORIZATION' and f.lastAttemptAt <= :archive))) order by f.id"""), String.class)
                        .setParameter("purge", now - ARCHIVE_MILLIS).setParameter("archive", now - RETENTION_MILLIS)
                        .setMaxResults(16).getResultList());
            }
            return due;
        });
        for (String id : ids) transaction.executeWithoutResult(status -> {
            int purged = entities.createQuery(Owner.of(id).query("""
                    delete from {entity} f where f.id=:id and f.archivedAt <= :cutoff"""))
                    .setParameter("id", id).setParameter("cutoff", now - ARCHIVE_MILLIS).executeUpdate();
            if (purged == 1) return;
            String executor = "system:workspace-fetch-retention";
            int archived = archiveSettled(id, executor, now, now - RETENTION_MILLIS);
            if (archived == 1) return;
            entities.createQuery(Owner.of(id).query("""
                    update {entity} f set f.phase='MANUAL_REVIEW', f.decision='AUTHORIZATION_EXPIRED',
                    f.completedAt=:now, f.archivedAt=:now, f.archivedBy=:executor, f.observed=null, f.result=null, f.resultTagObject=null
                    where f.id=:id and f.phase='AWAITING_AUTHORIZATION' and f.archivedAt is null and f.lastAttemptAt <= :cutoff"""))
                    .setParameter("id", id).setParameter("executor", executor).setParameter("now", now)
                    .setParameter("cutoff", now - RETENTION_MILLIS).executeUpdate();
        });
    }

    private int archiveSettled(String id, String actor, long now, long cutoff) {
        return entities.createQuery(Owner.of(id).query("""
                update {entity} f set f.archivedAt=:now, f.archivedBy=:actor, f.observed=null, f.result=null, f.resultTagObject=null
                where f.id=:id and f.archivedAt is null and f.completedAt <= :cutoff"""))
                .setParameter("id", id).setParameter("actor", actor).setParameter("now", now)
                .setParameter("cutoff", cutoff).executeUpdate();
    }

    RepositoryOperationLeases.Claim claim(String id, String executor, boolean respectBackoff) {
        return leases.claim(Owner.of(id).type,id,executor,respectBackoff);
    }

    View run(RepositoryOperationLeases.Claim claim, FetchStep fetch) throws IOException {
        try {
            return transaction.execute(status -> {
                var row = leases.locked(Owner.of(claim.operationId()).type,claim);
                leases.requireCurrent(row,claim);
                try {
                    var result = fetch.apply(row, () -> leases.requireCurrent(row,claim));
                    row.observed = result.sourceCommit();
                    row.result = result.trackedCommit();
                    row.finish("COMPLETED", null, clock.millis());
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

    private void failed(RepositoryOperationLeases.Claim claim, RuntimeException failure) {
        transaction.executeWithoutResult(status -> {
            var row = leases.locked(Owner.of(claim.operationId()).type,claim);
            if (row == null || !claim.lease().equals(row.lease)) return;
            long now = clock.millis();
            if (failure instanceof WorkspaceFetchAuthority.FreshLoginRequired) {
                row.phase = "AWAITING_AUTHORIZATION";
                row.decision = "FRESH_LOGIN_REQUIRED";
                row.nextAttemptAt = null;
                row.lease = null;
            } else if (failure instanceof UncheckedIOException io && io.getCause() instanceof GitUpstreamTransfer.Superseded superseded) {
                row.result = superseded.retainedCommit;
                row.finish("SUPERSEDED", "RETAINED_DESCENDANT", now);
            } else if (failure instanceof AccessDeniedException) row.finish("DENIED", "AUTHORITY_CHANGED", now);
            else if (failure instanceof UncheckedIOException io && io.getCause() instanceof BranchHeadConflictException) row.finish("MANUAL_REVIEW", "GIT_AUTHORITY_CHANGED", now);
            else if (row.attempts >= MAX_ATTEMPTS) row.finish("MANUAL_REVIEW", "ATTEMPTS_EXHAUSTED", now);
            else {
                row.phase = "RETRYABLE";
                row.decision = row instanceof CentralFetchOperation fork && fork.isInitialFork() ? "FORK_TRANSFER_INTERRUPTED" : "FETCH_INTERRUPTED";
                row.lease = null;
                row.nextAttemptAt = now + Duration.ofSeconds(15L << (row.attempts - 1)).toMillis();
            }
        });
    }

    private static View replay(RepositoryFetchOperation row, RepositoryContext selected, String principal, String command) {
        if (!row.ownedBy(selected, principal)) throw missing();
        if (row.archivedAt != null) throw new ResponseStatusException(HttpStatus.GONE, "FETCH_ARCHIVED");
        if (!row.fingerprint.equals(command)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Fetch command changed");
        return view(row);
    }

    private static View view(RepositoryFetchOperation row) {
        return new View(row.id, row.causeId, row.archivedAt == null ? row.phase : "ARCHIVED", row.context().repositoryId(), row.context().workspaceId(), row.branch, row.sourceBranch,
                row.forkPoint, row.requested, row.expected, row.observed, row.result, row.attempts, row.decision,
                row.createdAt, row.lastAttemptAt, row.completedAt, row.actor, row.executor, row.loginKind,
                row.policy, row.completedAt == null ? null : row.phase, row.archivedAt, row.archivedBy,
                row.sourceRepositoryId(), new GitUpstreamRef(row.sourceRepositoryId(), row.sourceBranch).name(),
                row.selectedTag, row.requestedTagObject, row.resultTagObject);
    }

    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Fetch operation unavailable"); }
    private static ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
