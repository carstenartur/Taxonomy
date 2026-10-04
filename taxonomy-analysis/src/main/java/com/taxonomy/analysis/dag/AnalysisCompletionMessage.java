package com.taxonomy.analysis.dag;

/** Completion of one executable unit; idempotent by {@link #taskId()}. */
public sealed interface AnalysisCompletionMessage extends AnalysisMessage
        permits SubtaxonomyAnalysisCompleted, RelationAnalysisCompleted {

    default AnalysisTaskId taskId() {
        return envelope().taskId();
    }

    /** True when the operation must not dispatch further work (cooperative stop/cancellation). */
    boolean stopsOperation();
}
