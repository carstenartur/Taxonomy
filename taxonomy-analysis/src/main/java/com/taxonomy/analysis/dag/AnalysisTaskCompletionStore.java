package com.taxonomy.analysis.dag;

import java.util.Optional;

/**
 * Durable, idempotent effect ledger of executed tasks.
 *
 * <p>A worker records a task's completion <em>before</em> it acknowledges the
 * delivery. A redelivered task whose completion is already recorded is answered
 * from this store instead of executing the work again.</p>
 */
public interface AnalysisTaskCompletionStore {

    /** Recorded completion of {@code taskId}, if any. */
    Optional<AnalysisCompletionMessage> find(AnalysisTaskId taskId);

    /**
     * Compare-and-set insert by task identity. Returns the completion that is
     * durably recorded afterwards: {@code completion} when it won, otherwise the
     * previously recorded completion, which is never overwritten.
     */
    AnalysisCompletionMessage recordIfAbsent(AnalysisCompletionMessage completion);
}
