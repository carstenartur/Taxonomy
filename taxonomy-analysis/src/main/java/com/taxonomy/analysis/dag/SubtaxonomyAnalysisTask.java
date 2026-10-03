package com.taxonomy.analysis.dag;

import java.util.List;
import java.util.Objects;

/** Score one taxonomy root (relevance plus hierarchical descent) for one requirement version. */
public record SubtaxonomyAnalysisTask(AnalysisEnvelope envelope, TaxonomyShardRoot root)
        implements AnalysisTaskMessage {

    public SubtaxonomyAnalysisTask {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(root, "root");
        envelope.requireType(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK);
        if (envelope.taskType() != AnalysisTaskType.SUBTAXONOMY_ANALYSIS
                || !AnalysisTaskId.subtaxonomy(envelope.operationId(), root).equals(envelope.taskId())
                || !envelope.roots().equals(List.of(root))) {
            throw new IllegalArgumentException("Sub-taxonomy task identity does not match its root");
        }
    }
}
