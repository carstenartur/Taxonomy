package com.taxonomy.analysis.operation;

/** Re-authorize versioned source evidence before exposing a retained native result. */
public interface AnalysisChildSourceAuthority {
    boolean current(AnalysisAdapterStore.Operation operation);
}
