package com.taxonomy.analysis.dag;

import java.util.Objects;

/**
 * Known-total progress derived from task state, e.g. "x/y tasks complete".
 * Carries no requirement text, user identity or scores.
 *
 * @param sequence monotonic per-operation sequence; consumers reject duplicates and regressions
 */
public record AnalysisProgressEvent(AnalysisEnvelope envelope, long sequence, AnalysisProgressPhase phase,
                                    int completedTasks, int totalTasks)
        implements AnalysisMessage {

    public AnalysisProgressEvent {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(phase, "phase");
        envelope.requireType(AnalysisMessageType.ANALYSIS_PROGRESS);
        if (sequence < 1) throw new IllegalArgumentException("sequence must be >= 1");
        if (totalTasks < 0 || completedTasks < 0 || completedTasks > totalTasks) {
            throw new IllegalArgumentException("Progress counts must satisfy 0 <= completed <= total");
        }
    }
}
