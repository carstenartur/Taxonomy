package com.taxonomy.analysis.dag;

/** Port for task completions, delivered to the operation coordinator. */
@FunctionalInterface
public interface AnalysisCompletionPublisher {
    void publish(AnalysisCompletionMessage completion);
}
