package com.taxonomy.analysis.dag;

/** Port for live progress and control events (fan-out; not replay authority). */
public interface AnalysisEventPublisher {

    void progress(AnalysisProgressEvent event);

    void cancellation(AnalysisCancellationEvent event);

    /** Publisher for deployments without live fan-out. */
    AnalysisEventPublisher NONE = new AnalysisEventPublisher() {
        @Override public void progress(AnalysisProgressEvent event) { }
        @Override public void cancellation(AnalysisCancellationEvent event) { }
    };
}
