package com.taxonomy.analysis.cluster;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Expected task, prepared input reference and committed result; never a process lease. */
@Entity @DynamicUpdate
@Table(name = "analysis_cluster_work", indexes = @Index(name = "idx_analysis_cluster_work_run", columnList = "operation_id,ordinal_number"))
public class ClusterAnalysisWork {
    @Id @Column(length = 64) String id;
    @Column(name = "operation_id", nullable = false, length = 128) String operationId;
    @Column(name = "task_id", nullable = false, length = 1200) String taskId;
    @Column(name = "task_type", nullable = false, length = 32) String taskType;
    @Column(name = "root_code", length = 64) String root;
    @Column(name = "ordinal_number", nullable = false) int ordinal;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "message_json", nullable = false) String messageJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "input_json") String inputJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "result_json") String resultJson;
    @Column(name = "failure_reason", length = 64) String failureReason;
    @Column(nullable = false, length = 32) String state;
    @Column(nullable = false) boolean settled;
    @Column(name = "delivery_attempts", nullable = false) int attempts;
    @Column(name = "started_at") Long startedAt;
    @Column(name = "finished_at") Long finishedAt;
    protected ClusterAnalysisWork() { }
}
