package com.taxonomy.analysis.cluster;

/** Durable operation lifecycle; terminal states cannot be replaced by late workers. */
public enum ClusterAnalysisState {
    QUEUED, RUNNING, RELATIONS, FINALIZING, COMPLETED, PARTIAL, CANCELLED, EXPIRED;

    public boolean terminal() { return this == COMPLETED || this == PARTIAL || this == CANCELLED || this == EXPIRED; }
}
