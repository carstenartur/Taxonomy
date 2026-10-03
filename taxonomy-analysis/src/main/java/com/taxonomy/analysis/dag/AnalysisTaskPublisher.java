package com.taxonomy.analysis.dag;

/**
 * Port for dispatching executable units. Implementations deliver at least once;
 * consumers make effects idempotent by {@link AnalysisTaskId}.
 */
@FunctionalInterface
public interface AnalysisTaskPublisher {
    void publish(AnalysisTaskMessage task);
}
