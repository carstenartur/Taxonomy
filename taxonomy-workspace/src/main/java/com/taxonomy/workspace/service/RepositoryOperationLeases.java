package com.taxonomy.workspace.service;

import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.UUID;

/** Claims commit independently. Hold the conditional row lock in the publication transaction through the ref CAS. */
final class RepositoryOperationLeases {
    record Claim(String operationId, String lease) { }

    private final EntityManager entities;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final long leaseMillis;
    private final int attemptLimit;

    RepositoryOperationLeases(EntityManager entities, TransactionTemplate transaction, Clock clock,
                              long leaseMillis, int attemptLimit) {
        this.entities = entities;
        if (transaction.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            throw new IllegalArgumentException("Repository operation claims require an independent transaction");
        }
        this.transaction = transaction;
        this.clock = clock;
        this.leaseMillis = leaseMillis;
        this.attemptLimit = attemptLimit;
    }

    Claim claim(Class<? extends LeasedRepositoryOperation> type, String id, String executor, boolean respectBackoff) {
        long now = clock.millis();
        String eligible = respectBackoff
                ? "(f.phase in ('ADMITTED','RUNNING','RETRYABLE') and f.nextAttemptAt <= :now)"
                : "(f.phase in ('ADMITTED','RETRYABLE','AWAITING_AUTHORIZATION') or (f.phase='RUNNING' and f.nextAttemptAt <= :now))";
        String lease = UUID.randomUUID().toString();
        String entity = entities.getMetamodel().entity(type).getName();
        return transaction.execute(status -> {
            int changed = entities.createQuery("""
                    update %s f set f.phase='RUNNING', f.attempts=f.attempts+1,
                    f.executor=:executor, f.lastAttemptAt=:now, f.nextAttemptAt=:until, f.lease=:lease
                    where f.id=:id and f.archivedAt is null and f.attempts < :limit and %s""".formatted(entity, eligible))
                    .setParameter("id", id).setParameter("executor", executor).setParameter("lease", lease)
                    .setParameter("now", now).setParameter("until", now + leaseMillis)
                    .setParameter("limit", attemptLimit).executeUpdate();
            if (changed == 1) return new Claim(id, lease);
            entities.createQuery("""
                    update %s f set f.phase='MANUAL_REVIEW', f.decision='ATTEMPTS_EXHAUSTED',
                    f.completedAt=:now, f.nextAttemptAt=null, f.lease=null
                    where f.id=:id and f.archivedAt is null and f.attempts >= :limit and %s""".formatted(entity, eligible))
                    .setParameter("id", id).setParameter("now", now).setParameter("limit", attemptLimit).executeUpdate();
            return null;
        });
    }

    /** The conditional write takes the physical row lock before any Git callback can see the lease. */
    <T extends LeasedRepositoryOperation> T locked(Class<T> type, Claim claim) {
        String entity = entities.getMetamodel().entity(type).getName();
        String id = claim.operationId();
        int locked = entities.createQuery("""
                update %s f set f.lease=:lease
                where f.id=:id and f.phase='RUNNING' and f.lease=:lease""".formatted(entity))
                .setParameter("id", claim.operationId()).setParameter("lease", claim.lease()).executeUpdate();
        if (locked != 1) return null;
        var row = entities.find(type,id);
        entities.refresh(row);
        return row;
    }

    void requireCurrent(LeasedRepositoryOperation row, Claim claim) {
        if (row == null || !claim.lease().equals(row.lease) || !"RUNNING".equals(row.phase)
                || row.nextAttemptAt == null || row.nextAttemptAt <= clock.millis()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Git operation ownership changed");
        }
    }

}
