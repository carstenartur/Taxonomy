package com.taxonomy.analysis.dispatch;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisTaskCompletionStore;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.AnalysisTaskIdentity;
import com.taxonomy.analysis.dag.PreparedAnalysisCompletion;
import com.taxonomy.analysis.dag.RelationAnalysisCompleted;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisCompleted;
import com.taxonomy.analysis.dag.json.AnalysisMessageCodec;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * The completion insert reserves the task before the business-result mutation.
 * Both become visible in one short transaction. A concurrent insert waits for
 * the winner, then reads it without invoking the losing effect. A failed effect
 * rolls back its reservation too, allowing broker redelivery to try again.
 * Provider computation and JMS acknowledgement stay outside this transaction.
 */
@Repository
public class JpaAnalysisTaskCompletionStore implements AnalysisTaskCompletionStore {
    private final EntityManager em;
    private final TransactionTemplate newTransaction;
    private final AnalysisMessageCodec codec = new AnalysisMessageCodec();
    private final Clock clock;

    @Autowired
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
    public AnalysisCompletionMessage commit(PreparedAnalysisCompletion<?> prepared) {
        Objects.requireNonNull(prepared, "prepared");
        AnalysisCompletionMessage completion = prepared.completion();
        return Objects.requireNonNull(newTransaction.execute(status -> {
            AnalysisCompletionMessage existing = read(completion.taskId());
            if (existing != null) return winner(completion, existing);
            String outcome = switch (completion) {
                case SubtaxonomyAnalysisCompleted root -> root.outcome().name();
                case RelationAnalysisCompleted relation -> relation.outcome().name();
            };
            String json = new String(codec.encode(completion), StandardCharsets.UTF_8);
            var duplicate = AnalysisInsertIfAbsent.insert(em, """
                    insert into analysis_task_completion
                      (id, task_id, operation_id, message_type, outcome, completion_json, recorded_at)
                    values (?, ?, ?, ?, ?, ?, ?)""", statement -> {
                statement.setString(1, AnalysisDispatchStore.key(completion.taskId()));
                statement.setString(2, completion.taskId().value());
                statement.setString(3, completion.envelope().operationId());
                statement.setString(4, completion.envelope().messageType().name());
                statement.setString(5, outcome);
                statement.setString(6, json);
                statement.setLong(7, clock.millis());
            });
            if (duplicate.isPresent()) {
                AnalysisCompletionMessage recorded = read(completion.taskId());
                if (recorded == null) {
                    throw new IllegalStateException("Concurrent completion is not visible", duplicate.get());
                }
                return winner(completion, recorded);
            }
            prepared.persistEffect().run();
            em.flush();
            return completion;
        }), "recorded completion");
    }

    private static AnalysisCompletionMessage winner(AnalysisCompletionMessage requested,
                                                    AnalysisCompletionMessage recorded) {
        AnalysisTaskIdentity.requireSameSource(requested.envelope(), recorded.envelope());
        return recorded;
    }

    private AnalysisCompletionMessage read(AnalysisTaskId taskId) {
        AnalysisTaskCompletionRecord record = em.find(AnalysisTaskCompletionRecord.class,
                AnalysisDispatchStore.key(taskId));
        if (record == null) return null;
        if (!record.taskId.equals(taskId.value())) throw new IllegalStateException("Completion key collision");
        return (AnalysisCompletionMessage) codec.decode(record.completionJson.getBytes(StandardCharsets.UTF_8));
    }
}
