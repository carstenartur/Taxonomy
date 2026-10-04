package com.taxonomy.analysis.dag;

/** Compute outside the result transaction; defer ALL durable mutations to the returned effect. */
@FunctionalInterface
public interface AnalysisTaskPreparation<T extends AnalysisTaskMessage, C extends AnalysisCompletionMessage> {
    PreparedAnalysisCompletion<C> prepare(T task);
}
