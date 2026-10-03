package com.taxonomy.analysis.dag;

/** Closed set of versioned analysis task/event contracts. */
public sealed interface AnalysisMessage
        permits AnalysisTaskMessage, AnalysisCompletionMessage, AnalysisProgressEvent, AnalysisCancellationEvent {

    AnalysisEnvelope envelope();
}
