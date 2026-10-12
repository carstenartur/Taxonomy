package com.taxonomy.analysis.cluster;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable logical evaluation. Request/answer previews are loaded only by the scoped detail endpoint. */
@Entity @DynamicUpdate
@Table(name = "analysis_cluster_call", uniqueConstraints = @UniqueConstraint(name = "uq_analysis_cluster_call_number", columnNames = {"operation_id", "call_number"}),
        indexes = @Index(name = "idx_analysis_cluster_call_run", columnList = "operation_id,call_number"))
public class ClusterAnalysisCall {
    @Id @Column(length = 64) String id;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "task_id", nullable = false, length = 1200) String taskId;
    @Column(name = "call_number", nullable = false) long callNumber;
    @Column(name = "execution_id", nullable = false, length = 36) String executionId;
    @Column(name = "logical_ordinal", nullable = false) int logicalOrdinal;
    @Column(name = "delivery_attempt", nullable = false) int deliveryAttempt;
    @Column(nullable = false, length = 128) String provider;
    @Column(length = 512) String node;
    @Column(nullable = false, length = 32) String state;
    @Column(name = "started_at", nullable = false) long startedAt;
    @Column(name = "duration_millis", nullable = false) long durationMillis;
    @Column(name = "event_revision", nullable = false) long eventRevision;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "prompt_text") String prompt;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "response_text") String response;
    @Column(name = "error_text", length = 1024) String error;
    @Column(name = "prompt_length", nullable = false) int promptLength;
    @Column(name = "response_length", nullable = false) int responseLength;
    protected ClusterAnalysisCall() { }
}
