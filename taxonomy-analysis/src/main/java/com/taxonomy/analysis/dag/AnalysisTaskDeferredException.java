package com.taxonomy.analysis.dag;

/**
 * An expected task is waiting for its predecessor. Consume this delivery without
 * recording a completion; the coordinator owns its later first publication.
 */
public final class AnalysisTaskDeferredException extends IllegalStateException {
    public AnalysisTaskDeferredException() { super("Analysis task awaits its predecessor"); }
}
