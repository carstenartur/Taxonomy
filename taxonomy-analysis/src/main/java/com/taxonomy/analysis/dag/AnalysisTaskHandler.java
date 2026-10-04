package com.taxonomy.analysis.dag;

/**
 * Worker-side handler for one task family. It validates authority, executes the
 * unit, records its result and returns the completion. The transport publishes
 * the completion and only then acknowledges the delivery.
 */
@FunctionalInterface
public interface AnalysisTaskHandler<T extends AnalysisTaskMessage, C extends AnalysisCompletionMessage> {
    C handle(T task);
}
