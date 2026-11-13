package com.taxonomy.workspace.service;

import com.taxonomy.workspace.service.WorkspacePublicationOperation.Status;
import com.taxonomy.workspace.service.WorkspacePublicationOperation.Step;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;

import static com.taxonomy.workspace.service.WorkspacePublicationOperation.Status.*;

/** Each accepted phase commits separately. A worker holds its operation row through the guarded external effect. */
@Service
public class WorkspacePublicationJournal {
    static final long LEASE = Duration.ofMinutes(5).toMillis();
    private static final int MAX_ATTEMPTS = 6;
    private final EntityManager entities;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final RepositoryOperationLeases leases;

    @Autowired
    public WorkspacePublicationJournal(EntityManager entities, PlatformTransactionManager transactions) {
        this(entities, transactions, Clock.systemUTC());
    }

    WorkspacePublicationJournal(EntityManager entities, PlatformTransactionManager transactions, Clock clock) {
        this.entities = entities;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        leases = new RepositoryOperationLeases(entities,transaction,clock,LEASE,MAX_ATTEMPTS);
    }

    record Source(String branch, String forkPoint, String central, String local, String tracking) {}
    record Claim(RepositoryOperationLeases.Claim lease, String executor) {}
    public record View(String operationId,String causationId,String status,String nextPhase,String originalActor,String lastExecutor,
                String repositoryId,String workspaceId,String branch,String sourceBranch,String expectedSourceCommit,String expectedLocalCommit,
                String preparedCommit,String resultCommit,int attempts,String decisionCode,long createdAt,Long lastAttemptAt,Long publishedAt,Long sourceObservedAt,Long completedAt,Long archivedAt,Long pinsReleasedAt,Long redactedAt,boolean pull) {}
    @FunctionalInterface interface PhaseAction { void run(WorkspacePublicationOperation row,Runnable checkLease) throws IOException; }
    @FunctionalInterface interface Snapshot { Source capture() throws IOException; }

    View admit(RepositoryContext context, WorkspaceFetchAuthority.Grant grant, boolean keepWorkspace,
               String key, String cause, Snapshot snapshot) throws IOException {
        return admit(context,grant,keepWorkspace,key,cause,snapshot,false);
    }

    View admit(RepositoryContext context, WorkspaceFetchAuthority.Grant grant, boolean keepWorkspace,
               String key, String cause, Snapshot snapshot, boolean pull) throws IOException {
        if (key != null && !key.matches("[A-Za-z0-9._~:-]{1,128}")) throw invalid("Invalid publication idempotency key");
        if (cause != null && !cause.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid("Invalid publication causation ID");
        // Legacy pull clients cannot send a retry ID. Reuse an unfinished original command in this exact scope.
        if (pull && key == null) {
            var unresolved = transaction.execute(tx -> entities.createQuery("""
                    select p from WorkspacePublicationOperation p where p.repository=:repository
                    and p.workspace=:workspace and p.localBranch=:branch and p.actor=:actor and p.username=:user
                    and p.policy=:policy and p.keepWorkspace=:takeSource
                    and p.archivedAt is null and p.completedAt is null order by p.createdAt,p.id
                    """, WorkspacePublicationOperation.class)
                    .setParameter("repository",context.repositoryId()).setParameter("workspace",context.workspaceId())
                    .setParameter("branch",context.branch()).setParameter("actor",grant.principalId())
                    .setParameter("user",context.username()).setParameter("policy",WorkspacePublicationOperation.LEGACY_PULL_POLICY)
                    .setParameter("takeSource",keepWorkspace).setMaxResults(1).getResultList());
            if (!unresolved.isEmpty()) return replay(unresolved.getFirst(),context,grant.principalId(),keepWorkspace,cause);
        }
        Source captured = pull && key == null ? snapshot.capture() : null;
        String token;
        if (key!=null) token="provided:"+key;
        else if (captured==null) token="generated:"+UUID.randomUUID();
        else token=String.join("\u0000","legacy-pull",Boolean.toString(keepWorkspace),captured.branch(),
                String.valueOf(captured.forkPoint()),String.valueOf(captured.central()),
                String.valueOf(captured.local()),String.valueOf(captured.tracking()));
        String id = com.taxonomy.identity.StableIdentityHash.sha256(String.join("\u0000",pull ? "workspace.pull" : "workspace.publish",
                context.repositoryWorkspaceScopeKey(),grant.principalId(),token));
        var previous = transaction.execute(tx -> entities.find(WorkspacePublicationOperation.class,id));
        if (previous != null) return replay(previous,context,grant.principalId(),keepWorkspace,cause);
        var source = captured == null ? snapshot.capture() : captured;
        try {
            return transaction.execute(tx -> {
                var row = new WorkspacePublicationOperation();
                row.id=id; row.cause=cause == null ? id : UUID.fromString(cause).toString();
                row.actor=grant.principalId(); row.loginKind=grant.loginKind(); row.binding=grant.bindingKey();
                row.policy=!pull ? WorkspacePublicationOperation.PUBLISH_POLICY
                        : key == null ? WorkspacePublicationOperation.LEGACY_PULL_POLICY : WorkspacePublicationOperation.PULL_POLICY; row.username=context.username();
                row.repository=context.repositoryId(); row.workspace=context.workspaceId(); row.localBranch=context.branch();
                row.sourceBranch=source.branch(); row.forkPoint=source.forkPoint(); row.inputSource=source.central();
                row.inputLocal=source.local(); row.inputTracking=source.tracking(); row.keepWorkspace=keepWorkspace;
                row.phase=ADMITTED.name(); row.step=Step.PREPARE; row.createdAt=clock.millis(); row.nextAttemptAt=row.createdAt;
                entities.persist(row); entities.flush();
                event(row,row.actor,"ADMITTED");
                return view(row);
            });
        } catch (RuntimeException failure) {
            var competing = transaction.execute(tx -> entities.find(WorkspacePublicationOperation.class,id));
            if (competing == null) throw failure;
            return replay(competing,context,grant.principalId(),keepWorkspace,cause);
        }
    }

    View get(RepositoryContext context,String actor,String id) {
        return transaction.execute(tx -> view(owned(context,actor,id)));
    }

    View archive(RepositoryContext selected,String actor,String id) {
        return transaction.execute(tx -> {
            owned(selected,actor,id);
            var row=entities.find(WorkspacePublicationOperation.class,id,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            entities.refresh(row);
            if (!row.belongsTo(selected,actor)) throw missing();
            if (row.completedAt==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"A live publication cannot be archived");
            if (row.archivedAt==null) {
                row.archivedAt=clock.millis();row.archivedBy=actor;event(row,actor,"ARCHIVED");
            }
            return view(row);
        });
    }

    /** Owner deletion takes this snapshot and retires its receipts in the same transaction as physical workspace deletion. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void archiveForWorkspaceRemoval(String workspace,String repository,String owner,java.util.function.Supplier<String> deletingActor) {
        var ids=entities.createQuery("""
                select p.id from WorkspacePublicationOperation p where p.workspace=:workspace and p.repository=:repository
                and p.username=:owner and p.archivedAt is null order by p.id""",String.class)
                .setParameter("workspace",workspace).setParameter("repository",repository).setParameter("owner",owner).getResultList();
        if (ids.isEmpty()) return;
        String actor=java.util.Objects.requireNonNull(deletingActor.get(),"Authenticated deleting actor");
        for (String id:ids) {
            if (entities.createQuery("""
                    update WorkspacePublicationOperation p set p.archivedAt=:now,p.archivedBy=:actor
                    where p.id=:id and p.workspace=:workspace and p.repository=:repository and p.username=:owner
                    and p.archivedAt is null""")
                    .setParameter("id",id).setParameter("workspace",workspace).setParameter("repository",repository)
                    .setParameter("owner",owner).setParameter("now",clock.millis()).setParameter("actor",actor).executeUpdate()!=1) continue;
            var row=entities.find(WorkspacePublicationOperation.class,id);
            entities.refresh(row);
            if (row.completedAt==null) settle(row,DENIED,"WORKSPACE_DELETED",actor);
            event(row,actor,"ARCHIVED");
        }
    }

    List<String> due() {
        return transaction.execute(tx -> entities.createQuery("""
                select p.id from WorkspacePublicationOperation p where p.phase in :states and p.archivedAt is null
                and p.nextAttemptAt <= :now order by p.nextAttemptAt,p.id""",String.class)
                .setParameter("states",List.of("ADMITTED","RUNNING","RETRYABLE")).setParameter("now",clock.millis()).setMaxResults(8).getResultList());
    }

    Claim claim(String id,String executor,boolean explicitRetry) {
        var claim=leases.claim(WorkspacePublicationOperation.class,id,executor,!explicitRetry,row -> {
            row.cleanupDueAt=row.completedAt;
            event(row,executor,row.decision);
        });
        return claim==null ? null : new Claim(claim,executor);
    }

    View step(Claim claim, PhaseAction action) throws IOException {
        try {
            return transaction.execute(tx -> {
                var row=locked(claim);
                try { action.run(row,() -> leases.requireCurrent(row,claim.lease())); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                var applied=row.step;
                if (applied==Step.SOURCE_REF || row.isPull() && applied==Step.WORKSPACE_REF) {
                    row.sourceObservedAt=clock.millis();
                }
                event(row,claim.executor(),"APPLIED");
                row.step=row.nextStep();
                if (row.step==Step.COMPLETE) settle(row,COMPLETED,"COMPLETED");
                else row.nextAttemptAt=clock.millis()+LEASE;
                return view(row);
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }

    View decide(Claim claim,Status status,String decision) {
        return decide(claim,status,decision,false,null);
    }

    View decide(Claim claim,Status status,String decision,boolean confirmedSourceAncestor,java.time.Instant sourceTime) {
        return transaction.execute(tx -> {
            var row=locked(claim);
            if (confirmedSourceAncestor && row.sourceObservedAt==null) row.sourceObservedAt=clock.millis();
            if (confirmedSourceAncestor && sourceTime!=null && row.publishedAt==null) row.publishedAt=sourceTime.toEpochMilli();
            if (status==SUPERSEDED && row.sourceObservedAt==null) settle(row,MANUAL_REVIEW,"WORKSPACE_ADVANCED_BEFORE_SOURCE");
            else if (status==RETRYABLE && row.attempts>=MAX_ATTEMPTS) settle(row,MANUAL_REVIEW,"ATTEMPTS_EXHAUSTED");
            else if (status==RETRYABLE || status==AWAITING_AUTHORIZATION) {
                row.phase=status.name(); row.decision=decision; row.lease=null;
                row.nextAttemptAt=status==RETRYABLE ? clock.millis()+Math.min(300_000,1000L << (row.attempts-1)) : null;
                event(row,claim.executor(),decision);
            } else settle(row,status,decision);
            return view(row);
        });
    }

    /** Retain a scope-owned idempotency tombstone and its append-only decision trail after sensitive ref details expire. */
    void expireDue() {
        long now=clock.millis();
        long archiveCutoff=now-Duration.ofDays(30).toMillis();
        long redactCutoff=now-Duration.ofDays(90).toMillis();
        var ids=transaction.execute(tx -> entities.createQuery("""
                select p.id from WorkspacePublicationOperation p where
                    (p.archivedAt is null and (p.completedAt <= :archive
                        or (p.phase='AWAITING_AUTHORIZATION' and p.lastAttemptAt <= :archive)))
                    or (p.archivedAt <= :redact and p.redactedAt is null and p.pinsReleasedAt is not null)
                order by p.id""",String.class).setParameter("archive",archiveCutoff)
                .setParameter("redact",redactCutoff).setMaxResults(16).getResultList());
        for (String id:ids) transaction.executeWithoutResult(tx -> {
            String actor="system:workspace-publication-retention";
            int archived=entities.createQuery("""
                    update WorkspacePublicationOperation p set p.archivedAt=:now,p.archivedBy=:actor
                    where p.id=:id and p.archivedAt is null and (p.completedAt <= :cutoff
                        or (p.phase='AWAITING_AUTHORIZATION' and p.lastAttemptAt <= :cutoff))""")
                    .setParameter("now",now).setParameter("actor",actor).setParameter("id",id)
                    .setParameter("cutoff",archiveCutoff).executeUpdate();
            if (archived==1) {
                var row=entities.find(WorkspacePublicationOperation.class,id);
                entities.refresh(row);
                if (row.completedAt==null) settle(row,MANUAL_REVIEW,"AUTHORIZATION_EXPIRED",actor);
                event(row,actor,"ARCHIVED");
                return;
            }
            int redacted=entities.createQuery("""
                    update WorkspacePublicationOperation p set p.redactedAt=:now,p.binding='REDACTED',
                        p.inputLocal=null,p.inputSource=null,p.inputTracking=null,p.expectedLocal=null,p.expectedTracking=null,
                        p.preparedLocal=null,p.preparedSource=null,p.preparedTracking=null,p.forkPoint=null,p.mergedDigest=null,
                        p.centralPortfolioDigest=null,p.localPortfolioDigest=null
                    where p.id=:id and p.archivedAt <= :cutoff and p.redactedAt is null and p.pinsReleasedAt is not null""")
                    .setParameter("now",now).setParameter("cutoff",redactCutoff).setParameter("id",id).executeUpdate();
            if (redacted==1) event(entities.find(WorkspacePublicationOperation.class,id),actor,"GIT_DETAILS_REDACTED");
        });
    }

    List<String> dueForCleanup() {
        return transaction.execute(tx -> entities.createQuery("""
                select p.id from WorkspacePublicationOperation p where p.completedAt is not null
                and p.pinsReleasedAt is null and p.cleanupDueAt <= :now order by p.cleanupDueAt,p.id""", String.class)
                .setParameter("now",clock.millis()).setMaxResults(8).getResultList());
    }

    @FunctionalInterface interface CleanupAction { void release(WorkspacePublicationOperation row) throws IOException; }

    void cleanup(String id, String executor, boolean requested, CleanupAction action) {
        transaction.executeWithoutResult(tx -> {
            // A real conditional write serializes concurrent cleaners on HSQL MVCC and server databases.
            String eligibility=requested ? "" : " and p.cleanupDueAt <= :now";
            var query=entities.createQuery("""
                    update WorkspacePublicationOperation p set p.cleanupDueAt=p.cleanupDueAt
                    where p.id=:id and p.completedAt is not null and p.pinsReleasedAt is null""" + eligibility)
                    .setParameter("id",id);
            if (!requested) query.setParameter("now",clock.millis());
            if (query.executeUpdate()!=1) return;
            var row=entities.find(WorkspacePublicationOperation.class,id);
            entities.refresh(row);
            try {
                action.release(row);
                row.pinsReleasedAt=clock.millis();
                row.cleanupDueAt=null;
                event(row,executor,"PINS_RELEASED");
            } catch (WorkspaceFetchAuthority.FreshLoginRequired | org.springframework.security.access.AccessDeniedException unavailable) {
                row.cleanupDueAt=null;
                event(row,executor,"PIN_CLEANUP_AUTHORIZATION_REQUIRED");
            } catch (IOException unavailable) {
                // Already removed private refs may safely be retried. Retain evidence and require a scoped operator retry.
                row.cleanupDueAt=null;
                event(row,executor,"PIN_CLEANUP_REVIEW_REQUIRED");
            }
        });
    }

    private WorkspacePublicationOperation locked(Claim claim) {
        var row=leases.locked(WorkspacePublicationOperation.class,claim.lease());
        leases.requireCurrent(row,claim.lease());
        return row;
    }

    private void settle(WorkspacePublicationOperation row,Status status,String decision) {
        settle(row,status,decision,row.executor);
    }

    private void settle(WorkspacePublicationOperation row,Status status,String decision,String actor) {
        row.phase=status.name();row.decision=decision;row.completedAt=clock.millis();row.lease=null;row.nextAttemptAt=null;
        row.cleanupDueAt=row.completedAt;
        event(row,actor,decision);
    }

    private static View replay(WorkspacePublicationOperation row,RepositoryContext context,String actor,boolean keep,String cause) {
        if (!row.belongsTo(context,actor)) throw missing();
        if (row.archivedAt!=null) throw new ResponseStatusException(HttpStatus.GONE,"Publication archived");
        if (row.keepWorkspace!=keep || cause!=null && !row.cause.equals(UUID.fromString(cause).toString())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key belongs to another command");
        return view(row);
    }

    private WorkspacePublicationOperation owned(RepositoryContext context,String actor,String id) {
        var row=entities.find(WorkspacePublicationOperation.class,id);
        if(row==null || !row.belongsTo(context,actor)) throw missing();
        return row;
    }

    private void event(WorkspacePublicationOperation row,String executor,String decision) {
        entities.persist(new WorkspacePublicationDecision(row,executor,clock.millis(),decision));
    }

    private static View view(WorkspacePublicationOperation row) {
        return new View(row.id,row.cause,row.archivedAt==null?row.phase:"ARCHIVED",row.step.name(),row.actor,row.executor,
                row.repository,row.workspace,row.localBranch,row.sourceBranch,row.inputSource,row.inputLocal,
                row.isPull() ? row.preparedLocal : row.preparedSource,
                row.sourceObservedAt==null ? null : row.isPull() ? row.preparedLocal : row.preparedSource,row.attempts,
                row.decision,row.createdAt,row.lastAttemptAt,row.publishedAt,row.sourceObservedAt,row.completedAt,row.archivedAt,row.pinsReleasedAt,row.redactedAt,row.isPull());
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Publication unavailable"); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
