package com.taxonomy.analysis.cluster;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable coordinator authority. Large frozen shard data lives in separate rows. */
@Entity @DynamicUpdate
@Table(name = "analysis_cluster_run", indexes = {
        @Index(name = "idx_analysis_cluster_owner", columnList = "username,created_at"),
        @Index(name = "idx_analysis_cluster_scope", columnList = "username,scope_key,created_at")})
public class ClusterAnalysisRun {
    @Id @Column(length = 128) String id;
    @Column(nullable = false, length = 160) String username;
    @Column(name = "scope_key", nullable = false, length = 64) String scopeKey;
    @Column(name = "project_id") Long projectId;
    @Column(name = "requirement_id") Long requirementId;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "context_json", nullable = false) String contextJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "command_json", nullable = false) String commandJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "view_json") String viewJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "result_json") String resultJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "relation_plan_json") String relationPlanJson;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) ClusterAnalysisState state;
    @Column(name = "total_roots", nullable = false) int totalRoots;
    @Column(name = "completed_roots", nullable = false) int completedRoots;
    @Column(name = "event_revision", nullable = false) long revision;
    @Column(name = "created_at", nullable = false) long createdAt;
    @Column(name = "updated_at", nullable = false) long updatedAt;
    @Version @Column(name = "row_version", nullable = false) long version;
    protected ClusterAnalysisRun() { }
}
