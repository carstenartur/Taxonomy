package com.taxonomy.analysis.dag;

import java.util.Optional;

/** Completion and business-result mutations share one atomic, task-keyed commit. */
public interface AnalysisTaskCompletionStore {
    Optional<AnalysisCompletionMessage> find(AnalysisTaskId taskId);

    /** Read a replay only when it belongs to this exact immutable task source. */
    default Optional<AnalysisCompletionMessage> find(AnalysisTaskMessage task) {
        var recorded = find(task.taskId());
        recorded.ifPresent(value -> AnalysisTaskIdentity.requireSameSource(task.envelope(), value.envelope()));
        return recorded;
    }

    /**
     * Insert-only completion for a task with no additional durable effects.
     * Workers with result mutations must use commit instead.
     */
    default AnalysisCompletionMessage recordIfAbsent(AnalysisCompletionMessage completion) {
        return commit(PreparedAnalysisCompletion.withoutEffects(completion));
    }

    /**
     * Commit the prepared result and completion atomically, or return the existing
     * winner without invoking persistEffect. The effect must participate in this
     * transaction; no acknowledgement is permitted until this method succeeds.
     */
    AnalysisCompletionMessage commit(PreparedAnalysisCompletion<?> prepared);
}
