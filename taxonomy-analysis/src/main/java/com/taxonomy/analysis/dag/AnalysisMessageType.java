package com.taxonomy.analysis.dag;

/**
 * Explicit discriminator of the versioned message contract.
 *
 * <p>Serialized messages carry this value instead of Java class names, so a
 * consumer can only materialize one of the declared contract records.</p>
 */
public enum AnalysisMessageType {
    SUBTAXONOMY_ANALYSIS_TASK(SubtaxonomyAnalysisTask.class),
    SUBTAXONOMY_ANALYSIS_COMPLETED(SubtaxonomyAnalysisCompleted.class),
    RELATION_ANALYSIS_TASK(RelationAnalysisTask.class),
    RELATION_ANALYSIS_COMPLETED(RelationAnalysisCompleted.class),
    ANALYSIS_PROGRESS(AnalysisProgressEvent.class),
    ANALYSIS_CANCELLATION(AnalysisCancellationEvent.class);

    private final Class<? extends AnalysisMessage> contract;

    AnalysisMessageType(Class<? extends AnalysisMessage> contract) {
        this.contract = contract;
    }

    public Class<? extends AnalysisMessage> contract() {
        return contract;
    }
}
