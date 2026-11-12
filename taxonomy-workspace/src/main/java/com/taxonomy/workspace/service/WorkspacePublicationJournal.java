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
                String preparedCommit,String resultCommit,int attempts,String decisionCode,long createdAt,Long lastAttemptAt,Long publishedAt,Long completedAt,Long archivedAt) {}
    @FunctionalInterface interface PhaseAction { void run(WorkspacePublicationOperation row,Runnable checkLease) throws IOException; }
    @FunctionalInterface interface Snapshot { Source capture() throws IOException; }

    View admit(RepositoryContext context, WorkspaceFetchAuthority.Grant grant, boolean keepWorkspace,
               String key, String cause, Snapshot snapshot) throws IOException {
        if (key != null && !key.matches("[A-Za-z0-9._~:-]{1,128}")) throw invalid("Invalid publication idempotency key");
        if (cause != null && !cause.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw invalid("Invalid publication causation ID");
        // A keyless legacy request carries no frozen input. Each submission receives its own recoverable operation.
        String token = key == null ? "generated:" + UUID.randomUUID() : "provided:" + key;
        String id = com.taxonomy.identity.StableIdentityHash.sha256(String.join("\u0000","workspace.publish",
                context.repositoryWorkspaceScopeKey(),grant.principalId(),token));
        var previous = transaction.execute(tx -> entities.find(WorkspacePublicationOperation.class,id));
        if (previous != null) return replay(previous,context,grant.principalId(),keepWorkspace);
        var source = snapshot.capture();
        try {
            return transaction.execute(tx -> {
                var row = new WorkspacePublicationOperation();
                row.id=id; row.cause=cause == null ? id : UUID.fromString(cause).toString();
                row.actor=grant.principalId(); row.loginKind=grant.loginKind(); row.binding=grant.bindingKey();
                row.policy="ARCHITECTURE_PUBLISH+CONTRIBUTOR"; row.username=context.username();
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
            return replay(competing,context,grant.principalId(),keepWorkspace);
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

    List<String> due() {
        return transaction.execute(tx -> entities.createQuery("""
                select p.id from WorkspacePublicationOperation p where p.phase in :states and p.archivedAt is null
                and p.nextAttemptAt <= :now order by p.nextAttemptAt,p.id""",String.class)
                .setParameter("states",List.of("ADMITTED","RUNNING","RETRYABLE")).setParameter("now",clock.millis()).setMaxResults(8).getResultList());
    }

    Claim claim(String id,String executor,boolean explicitRetry) {
        var claim=leases.claim(WorkspacePublicationOperation.class,id,executor,!explicitRetry);
        return claim==null ? null : new Claim(claim,executor);
    }

    View step(Claim claim, PhaseAction action) throws IOException {
        try {
            return transaction.execute(tx -> {
                var row=locked(claim);
                try { action.run(row,() -> leases.requireCurrent(row,claim.lease())); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                var applied=row.step;
                if (applied==Step.SOURCE_REF && row.publishedAt==null) row.publishedAt=clock.millis();
                event(row,claim.executor(),"APPLIED");
                row.step=Step.values()[applied.ordinal()+1];
                if (row.step==Step.COMPLETE) settle(row,COMPLETED,"COMPLETED");
                else row.nextAttemptAt=clock.millis()+LEASE;
                return view(row);
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }

    View decide(Claim claim,Status status,String decision) {
        return transaction.execute(tx -> {
            var row=locked(claim);
            if (status==RETRYABLE && row.attempts>=MAX_ATTEMPTS) settle(row,MANUAL_REVIEW,"ATTEMPTS_EXHAUSTED");
            else if (status==RETRYABLE || status==AWAITING_AUTHORIZATION) {
                row.phase=status.name(); row.decision=decision; row.lease=null;
                row.nextAttemptAt=status==RETRYABLE ? clock.millis()+Math.min(300_000,1000L << (row.attempts-1)) : null;
                event(row,claim.executor(),decision);
            } else settle(row,status,decision);
            return view(row);
        });
    }

    private WorkspacePublicationOperation locked(Claim claim) {
        var row=leases.locked(WorkspacePublicationOperation.class,claim.lease());
        leases.requireCurrent(row,claim.lease());
        return row;
    }

    private void settle(WorkspacePublicationOperation row,Status status,String decision) {
        row.phase=status.name();row.decision=decision;row.completedAt=clock.millis();row.lease=null;row.nextAttemptAt=null;
        event(row,row.executor,decision);
    }

    private static View replay(WorkspacePublicationOperation row,RepositoryContext context,String actor,boolean keep) {
        if (!row.belongsTo(context,actor)) throw missing();
        if (row.archivedAt!=null) throw new ResponseStatusException(HttpStatus.GONE,"Publication archived");
        if (row.keepWorkspace!=keep) throw new ResponseStatusException(HttpStatus.CONFLICT,"Idempotency key belongs to another command");
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
                row.repository,row.workspace,row.localBranch,row.sourceBranch,row.inputSource,row.inputLocal,row.preparedSource,row.publishedAt==null ? null : row.preparedSource,row.attempts,
                row.decision,row.createdAt,row.lastAttemptAt,row.publishedAt,row.completedAt,row.archivedAt);
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Publication unavailable"); }
    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
