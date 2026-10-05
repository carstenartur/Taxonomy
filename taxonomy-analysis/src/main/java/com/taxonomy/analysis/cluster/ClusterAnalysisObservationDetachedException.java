package com.taxonomy.analysis.cluster;

/** The web observer ended; this is deliberately not an analysis cancellation. */
public final class ClusterAnalysisObservationDetachedException extends RuntimeException {
    public ClusterAnalysisObservationDetachedException() {
        super("Analysis observation detached; the durable operation continues");
    }
}
