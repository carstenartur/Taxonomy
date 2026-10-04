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

    /** The same task as observed on delivery {@code deliveryAttempt}; identities are unchanged. */
    default AnalysisTaskMessage atAttempt(int deliveryAttempt) {
        AnalysisEnvelope observed = envelope().withAttempt(deliveryAttempt);
        if (observed == envelope()) return this;
        return switch (this) {
            case SubtaxonomyAnalysisTask task -> new SubtaxonomyAnalysisTask(observed, task.root());
            case RelationAnalysisTask task -> new RelationAnalysisTask(observed, task.targetRoots(),
                    task.prerequisiteTasks());
        };
    }

    /** Root whose shard owns this task's execution; {@code null} for multi-root/whole-evidence work. */
    default TaxonomyShardRoot routingRoot() {
        var roots = envelope().roots();
        return roots.size() == 1 ? roots.get(0) : null;
    }
}
