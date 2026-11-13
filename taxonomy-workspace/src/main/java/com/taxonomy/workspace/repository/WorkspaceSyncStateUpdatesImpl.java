package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.WorkspaceBranchSyncState;
import com.taxonomy.workspace.model.WorkspaceSyncScope;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;

/** Persist Git observations separately from Git I/O, in the originating branch and actor scope. */
public class WorkspaceSyncStateUpdatesImpl implements WorkspaceSyncStateUpdates {
    private final EntityManager entities;
    private final TransactionTemplate transaction;

    public WorkspaceSyncStateUpdatesImpl(EntityManager entities, PlatformTransactionManager manager) {
        this.entities = entities;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(readOnly = true)
    public SyncState readScope(WorkspaceSyncScope scope) {
        var row = entities.find(WorkspaceBranchSyncState.class, scope.key());
        return (row == null ? new WorkspaceBranchSyncState(scope) : row).view();
    }

    @Override
    public void recordSync(WorkspaceSyncScope scope, String commit, Instant at, boolean published) {
        observe(scope, at, () -> {
            entities.createQuery("""
                    update WorkspaceBranchSyncState s set s.lastSyncedCommitId=:commit, s.lastSyncTimestamp=:at
                    where s.scopeKey=:key and (s.lastSyncTimestamp is null or s.lastSyncTimestamp <= :at)""")
                    .setParameter("commit", commit).setParameter("at", at).setParameter("key", scope.key()).executeUpdate();
            if (published) {
                entities.createQuery("""
                        update WorkspaceBranchSyncState s set s.lastPublishedCommitId=:commit, s.lastPublishTimestamp=:at
                        where s.scopeKey=:key and (s.lastPublishTimestamp is null or s.lastPublishTimestamp <= :at)""")
                        .setParameter("commit", commit).setParameter("at", at).setParameter("key", scope.key()).executeUpdate();
            }
            updateProjection(scope, at, published ? 0 : null, "UP_TO_DATE");
        });
    }

    @Override
    public void recordLocalChanges(WorkspaceSyncScope scope, int ahead, int behind, Instant at) {
        String status = ahead > 0 ? (behind > 0 ? "DIVERGED" : "AHEAD") : (behind > 0 ? "BEHIND" : "UP_TO_DATE");
        observe(scope, at, () -> updateProjection(scope, at, ahead, status));
    }

    private void updateProjection(WorkspaceSyncScope scope, Instant at, Integer count, String status) {
        var update = entities.createQuery("""
                update WorkspaceBranchSyncState s set s.syncStatus=:status, s.updatedAt=:at,
                s.unpublishedCommitCount=coalesce(:count, s.unpublishedCommitCount)
                where s.scopeKey=:key and (s.updatedAt is null or s.updatedAt <= :at)""");
        update.setParameter("status", status).setParameter("at", at).setParameter("count", count)
                .setParameter("key", scope.key()).executeUpdate();
    }

    private void observe(WorkspaceSyncScope scope, Instant at, Runnable update) {
        // A first insert may race another writer. Retry only recognized duplicate/serialization failures.
        for (int attempt = 1; ; attempt++) {
            try {
                transaction.executeWithoutResult(tx -> {
                    if (entities.find(WorkspaceBranchSyncState.class, scope.key()) == null) {
                        var row = new WorkspaceBranchSyncState(scope);
                        row.setCreatedAt(at);
                        entities.persist(row);
                        entities.flush();
                    }
                    update.run();
                });
                return;
            } catch (RuntimeException failure) {
                if (attempt == 3 || !retryable(failure)) throw failure;
            }
        }
    }

    private static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("23505".equals(state) || "40001".equals(state) || "40P01".equals(state)) return true;
                if ("23000".equals(state) && (sql.getErrorCode() == 1 || sql.getErrorCode() == 2601
                        || sql.getErrorCode() == 2627)) return true;
            }
        }
        return false;
    }
}
