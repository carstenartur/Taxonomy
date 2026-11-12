package com.taxonomy.analysis.cluster;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Retained content-free operation events. Broker multicast is only an acceleration. */
@Entity
@Table(name = "analysis_cluster_event", indexes = @Index(name = "idx_analysis_cluster_event_run", columnList = "operation_id,event_revision"),
        uniqueConstraints = @UniqueConstraint(name = "uq_analysis_cluster_event_revision", columnNames = {"operation_id", "event_revision"}))
public class ClusterAnalysisEvent {
    /** The event replay API exposes at most this many recent progress notifications. */
    static final int RETAINED_COUNT = 1000;

    static long firstRetainedRevision(long latest) {
        return Math.max(1, latest - RETAINED_COUNT + 1);
    }

    /** Older stores and archives may still have complete history starting at revision one. */
    static boolean follows(long previous, long next, long latest) {
        return next == previous + 1 || (previous == 0 && next == firstRetainedRevision(latest));
    }

    @Id @Column(length = 64) String id;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "event_revision", nullable = false) long revision;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "event_json", nullable = false) String eventJson;
    protected ClusterAnalysisEvent() { }
}
