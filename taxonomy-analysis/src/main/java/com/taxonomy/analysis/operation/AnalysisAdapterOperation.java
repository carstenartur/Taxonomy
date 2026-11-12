package com.taxonomy.analysis.operation;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A receipt is committed before invoking a native provider-backed analysis. */
@Entity
@Table(name = "analysis_adapter_operation", indexes = @Index(name = "idx_adapter_retention", columnList = "state,updated_at"),
       uniqueConstraints = @UniqueConstraint(name = "uq_adapter_command", columnNames = "command_key"))
public class AnalysisAdapterOperation {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 36) String actor;
    @Column(name = "repository_id", nullable = false, length = 320) String repository;
    @Column(name = "workspace_id", length = 320) String workspace;
    @Column(name = "branch_name", nullable = false, length = 512) String branch;
    @Column(nullable = false, length = 48) String kind;
    @Column(name = "source_commit", nullable = false, length = 64) String sourceCommit;
    @Column(name = "command_key", nullable = false, length = 64) String commandKey;
    @Column(name = "request_key", nullable = false, length = 64) String requestKey;
    @Column(nullable = false, length = 64) String fingerprint;
    @Column(nullable = false, length = 16) String state;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "result_json") String resultJson;
    @Column(name = "failure_code", length = 48) String failureCode;
    @Column(name = "created_at", nullable = false) long createdAt;
    @Column(name = "updated_at", nullable = false) long updatedAt;
    @Version @Column(name = "row_version", nullable = false) long revision;
}
