package com.taxonomy.analysis.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Insert-only idempotency record of one executed task. The primary key is the
 * task identity digest, so a concurrent or redelivered execution cannot record a
 * second effect.
 */
@Entity
@Table(name = "analysis_task_completion",
        indexes = @Index(name = "idx_analysis_completion_operation", columnList = "operation_id"))
public class AnalysisTaskCompletionRecord {
    @Id @Column(length = 64) String id;
    @Column(name = "task_id", nullable = false, length = 1200) String taskId;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "message_type", nullable = false, length = 64) String messageType;
    @Column(nullable = false, length = 32) String outcome;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "completion_json", nullable = false) String completionJson;
    @Column(name = "recorded_at", nullable = false) long recordedAt;

    protected AnalysisTaskCompletionRecord() { }
}
