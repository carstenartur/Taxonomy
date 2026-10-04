package com.taxonomy.analysis.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable intent to deliver one task to the broker, written in the same database
 * transaction as the operation's task graph. Holds the versioned JSON task
 * contract (identifiers only, never credentials, requirement text or prompts).
 */
@Entity
@Table(name = "analysis_dispatch_intent", indexes = {
        @Index(name = "idx_analysis_dispatch_status", columnList = "status,created_at"),
        @Index(name = "idx_analysis_dispatch_operation", columnList = "operation_id")})
public class AnalysisDispatchIntent {
    /** SHA-256 of the task identity: a bounded, portable primary key. */
    @Id @Column(length = 64) String id;
    @Column(name = "task_id", nullable = false, length = 1200) String taskId;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "task_type", nullable = false, length = 32) String taskType;
    @Column(name = "routing_root", length = 64) String routingRoot;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "message_json", nullable = false) String messageJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) AnalysisDispatchStatus status;
    @Column(name = "dispatch_attempts", nullable = false) int dispatchAttempts;
    @Column(name = "created_at", nullable = false) long createdAt;
    @Column(name = "updated_at", nullable = false) long updatedAt;
    @Column(name = "dispatched_at") Long dispatchedAt;
    /** Typed failure category only; never broker addresses, credentials or payload content. */
    @Column(name = "failure_kind", length = 64) String failureKind;
    @Version @Column(name = "row_version", nullable = false) long version;

    protected AnalysisDispatchIntent() { }
}
