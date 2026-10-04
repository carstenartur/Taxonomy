package com.taxonomy.analysis.dag;

/** An executable unit with a deterministic {@link AnalysisTaskId}. */
public sealed interface AnalysisTaskMessage extends AnalysisMessage
        permits SubtaxonomyAnalysisTask, RelationAnalysisTask {

    default AnalysisTaskId taskId() {
        return envelope().taskId();
    }

    default AnalysisTaskType taskType() {
        return envelope().taskType();
    }
}
