package com.taxonomy.analysis.cluster;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A committed transport admission; null observation is unresolved evidence, never inferred success or zero usage. */
@Entity @DynamicUpdate
@Table(name = "analysis_cluster_attempt", indexes = @Index(name = "idx_analysis_cluster_attempt_call", columnList = "operation_id,call_id,started_at"))
public class ClusterAnalysisAttempt {
    @Id @Column(length = 36) String id;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "call_id", nullable = false, length = 64) String callId;
    @Column(name = "invocation_id", nullable = false, length = 36) String invocationId;
    @Column(nullable = false, length = 128) String provider;
    @Column(nullable = false, length = 32) String source;
    @Column(name = "retry_index", nullable = false) int retryIndex;
    @Column(name = "started_at", nullable = false) long startedAt;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "observation_json") String observationJson;
    protected ClusterAnalysisAttempt() { }
}
