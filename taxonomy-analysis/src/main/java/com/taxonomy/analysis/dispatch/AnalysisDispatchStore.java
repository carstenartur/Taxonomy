package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.AnalysisMessage;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.AnalysisTaskIdentity;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Short database transactions around dispatch intents. Broker I/O is never
 * executed inside these transactions.
 */
@Repository
public class AnalysisDispatchStore {

    /** Bounded, non-sensitive view of one intent. */
    public record Intent(String id, AnalysisTaskId taskId, AnalysisDispatchStatus status, String messageJson,
                         int dispatchAttempts, long createdAt, String failureKind) { }

    /** Keyset cursor of a bounded recovery scan. */
    public record Cursor(long createdAt, String id) {
        public static final Cursor START = new Cursor(Long.MIN_VALUE, "");
    }

    private final EntityManager em;
    private final TransactionTemplate newTransaction;
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private final Clock clock;

    @Autowired
    public AnalysisDispatchStore(EntityManager em, PlatformTransactionManager transactions) {
        this(em, transactions, Clock.systemUTC());
    }

    AnalysisDispatchStore(EntityManager em, PlatformTransactionManager transactions, Clock clock) {
        this.em = Objects.requireNonNull(em, "em");
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stable, bounded primary key of a task identity. */
    public static String key(AnalysisTaskId taskId) {
        return RequirementReference.sha256(taskId.value());
    }

    /**
     * Persist intents in the caller's transaction (normally the coordinator's
     * operation transaction). Idempotent by task identity: an existing intent is
     * never replaced. Returns the keys that still need publishing, in input order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> recordIntents(Collection<? extends AnalysisTaskMessage> tasks) {
        var input = List.copyOf(tasks);
        var pending = new HashSet<String>();
        long now = clock.millis();
        // Acquire insert locks in stable order even when callers submit reversed batches.
        // Publication still follows the caller's original order.
        for (AnalysisTaskMessage task : input.stream()
                .sorted(Comparator.comparing(t -> key(t.taskId()))).toList()) {
            String id = key(task.taskId());
            AnalysisDispatchIntent existing = em.find(AnalysisDispatchIntent.class, id);
            if (existing == null) {
                String json = new String(codec.encode(task), StandardCharsets.UTF_8);
                var duplicate = AnalysisInsertIfAbsent.insert(em, """
                        insert into analysis_dispatch_intent
                          (id, task_id, operation_id, task_type, routing_root, message_json, status,
                           dispatch_attempts, created_at, updated_at, row_version)
                        values (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0)""", statement -> {
                    statement.setString(1, id);
                    statement.setString(2, task.taskId().value());
                    statement.setString(3, task.envelope().operationId());
                    statement.setString(4, task.taskType().name());
                    TaxonomyShardRoot root = task.routingRoot();
                    statement.setString(5, root == null ? null : root.code());
                    statement.setString(6, json);
                    statement.setString(7, AnalysisDispatchStatus.DISPATCH_PENDING.name());
                    statement.setLong(8, now);
                    statement.setLong(9, now);
                });
                if (duplicate.isEmpty()) {
                    pending.add(id);
                    continue;
                }
                existing = em.find(AnalysisDispatchIntent.class, id);
                if (existing == null) {
                    throw new IllegalStateException("Concurrent dispatch intent is not visible", duplicate.get());
                }
            }
            if (!existing.taskId.equals(task.taskId().value())) {
                throw new IllegalStateException("Dispatch intent key collision");
            }
            AnalysisTaskIdentity.requireSameSource(task.envelope(), decode(view(existing)).envelope());
            if (existing.status.recoverable()) pending.add(id);
        }
        return input.stream().map(task -> key(task.taskId())).filter(pending::contains).toList();
    }

    /** Same as {@link #recordIntents} in a dedicated transaction, for callers without one. */
    public List<String> recordIntentsInNewTransaction(Collection<? extends AnalysisTaskMessage> tasks) {
        return newTransaction.execute(status -> recordIntents(tasks));
    }

    public Optional<Intent> find(String id) {
        return Optional.ofNullable(newTransaction.execute(status -> {
            AnalysisDispatchIntent intent = em.find(AnalysisDispatchIntent.class, id);
            return intent == null ? null : view(intent);
        }));
    }

    /** CAS acknowledgement: only a recoverable intent becomes {@code DISPATCHED}. */
    public boolean acknowledge(String id) {
        return transition(id, AnalysisDispatchStatus.DISPATCHED, null);
    }

    /** The broker was unreachable; keep the intent for the next recovery trigger. */
    public boolean markWaiting(String id, String failureKind) {
        return transition(id, AnalysisDispatchStatus.WAITING_FOR_BROKER, failureKind);
    }

    /** The intent can never be published; make it visible instead of retrying forever. */
    public boolean markFailed(String id, String failureKind) {
        return transition(id, AnalysisDispatchStatus.DISPATCH_FAILED, failureKind);
    }

    private boolean transition(String id, AnalysisDispatchStatus target, String failureKind) {
        long now = clock.millis();
        Integer updated = newTransaction.execute(status -> em.createQuery("""
                        update AnalysisDispatchIntent i
                           set i.status = :target, i.updatedAt = :now,
                               i.dispatchAttempts = i.dispatchAttempts + 1,
                               i.dispatchedAt = :dispatchedAt,
                               i.failureKind = :failureKind, i.version = i.version + 1
                         where i.id = :id and i.status in (:pending, :waiting)""")
                .setParameter("target", target)
                .setParameter("now", now)
                .setParameter("dispatchedAt", target == AnalysisDispatchStatus.DISPATCHED ? Long.valueOf(now) : null)
                .setParameter("failureKind", failureKind == null ? null : bounded(failureKind))
                .setParameter("id", id)
                .setParameter("pending", AnalysisDispatchStatus.DISPATCH_PENDING)
                .setParameter("waiting", AnalysisDispatchStatus.WAITING_FOR_BROKER)
                .executeUpdate());
        return updated != null && updated == 1;
    }

    /** One bounded page of recoverable intents after {@code cursor}, oldest first. */
    public List<Intent> recoverable(Cursor cursor, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
        return newTransaction.execute(status -> em.createQuery("""
                        select i from AnalysisDispatchIntent i
                         where i.status in (:pending, :waiting)
                           and (i.createdAt > :createdAt or (i.createdAt = :createdAt and i.id > :id))
                         order by i.createdAt, i.id""", AnalysisDispatchIntent.class)
                .setParameter("pending", AnalysisDispatchStatus.DISPATCH_PENDING)
                .setParameter("waiting", AnalysisDispatchStatus.WAITING_FOR_BROKER)
                .setParameter("createdAt", cursor.createdAt())
                .setParameter("id", cursor.id())
                .setMaxResults(limit)
                .getResultList().stream().map(AnalysisDispatchStore::view).toList());
    }

    public long count(AnalysisDispatchStatus status) {
        return newTransaction.execute(tx -> em.createQuery(
                        "select count(i) from AnalysisDispatchIntent i where i.status = :status", Long.class)
                .setParameter("status", status).getSingleResult());
    }

    AnalysisMessage decode(Intent intent) {
        return codec.decode(intent.messageJson().getBytes(StandardCharsets.UTF_8));
    }

    private static Intent view(AnalysisDispatchIntent intent) {
        return new Intent(intent.id, new AnalysisTaskId(intent.taskId), intent.status, intent.messageJson,
                intent.dispatchAttempts, intent.createdAt, intent.failureKind);
    }

    private static String bounded(String failureKind) {
        return failureKind.length() <= 64 ? failureKind : failureKind.substring(0, 64);
    }
}
