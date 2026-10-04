package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisTaskCompletionStore;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.RelationAnalysisCompleted;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisCompleted;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed idempotent effect ledger. The insert is its own short
 * transaction so it is durable before the worker acknowledges the delivery; a
 * concurrent duplicate loses on the primary key and observes the winner.
 */
@Repository
public class JpaAnalysisTaskCompletionStore implements AnalysisTaskCompletionStore {

    private final EntityManager em;
    private final TransactionTemplate newTransaction;
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private final Clock clock;

    public JpaAnalysisTaskCompletionStore(EntityManager em, PlatformTransactionManager transactions) {
        this(em, transactions, Clock.systemUTC());
    }

    JpaAnalysisTaskCompletionStore(EntityManager em, PlatformTransactionManager transactions, Clock clock) {
        this.em = Objects.requireNonNull(em, "em");
        this.newTransaction = new TransactionTemplate(transactions);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<AnalysisCompletionMessage> find(AnalysisTaskId taskId) {
        return Optional.ofNullable(newTransaction.execute(status -> read(taskId)));
    }

    @Override
    public AnalysisCompletionMessage recordIfAbsent(AnalysisCompletionMessage completion) {
        Objects.requireNonNull(completion, "completion");
        try {
            AnalysisCompletionMessage recorded = newTransaction.execute(status -> {
                AnalysisCompletionMessage existing = read(completion.taskId());
                if (existing != null) return existing;
                var record = new AnalysisTaskCompletionRecord();
                record.id = AnalysisDispatchStore.key(completion.taskId());
                record.taskId = completion.taskId().value();
                record.operationId = completion.envelope().operationId();
                record.messageType = completion.envelope().messageType().name();
                record.outcome = switch (completion) {
                    case SubtaxonomyAnalysisCompleted root -> root.outcome().name();
                    case RelationAnalysisCompleted relation -> relation.outcome().name();
                };
                record.completionJson = new String(codec.encode(completion), StandardCharsets.UTF_8);
                record.recordedAt = clock.millis();
                em.persist(record);
                em.flush();
                return completion;
            });
            return Objects.requireNonNull(recorded, "recorded completion");
        } catch (DataIntegrityViolationException | jakarta.persistence.PersistenceException concurrentInsert) {
            // A concurrent worker won the primary key. Its record is the durable effect.
            return find(completion.taskId()).orElseThrow(() -> concurrentInsert);
        }
    }

    private AnalysisCompletionMessage read(AnalysisTaskId taskId) {
        AnalysisTaskCompletionRecord record = em.find(AnalysisTaskCompletionRecord.class,
                AnalysisDispatchStore.key(taskId));
        if (record == null) return null;
        if (!record.taskId.equals(taskId.value())) throw new IllegalStateException("Completion key collision");
        return (AnalysisCompletionMessage) codec.decode(record.completionJson.getBytes(StandardCharsets.UTF_8));
    }
}
