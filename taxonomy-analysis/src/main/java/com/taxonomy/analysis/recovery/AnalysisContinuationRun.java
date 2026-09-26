package com.taxonomy.analysis.recovery;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable state only; no live worker or security context is retained in this entity. */
@Entity
@Table(name = "analysis_continuation", indexes = @Index(name = "idx_analysis_cont_scope", columnList = "workspace_id,username"))
public class AnalysisContinuationRun {
    @Id @Column(length = 36) String id;
    @Column(nullable = false, length = 160) String username;
    @Column(name = "workspace_id", nullable = false, length = 320) String workspaceId;
    @Column(name = "branch_name", nullable = false, length = 512) String branchName;
    @Column(name = "repository_id", nullable = false, length = 320) String repositoryId;
    @Column(name = "input_hash", nullable = false, length = 64) String inputHash;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "request_json", nullable = false) String requestJson;
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR) @Column(name = "result_json") String resultJson;
    @Column(nullable = false, length = 32) String state;
    @Column(name = "claim_token", length = 36) String claimToken;
    @Column(name = "claim_until", nullable = false) long claimUntil;
    @Column(name = "updated_at", nullable = false) long updatedAt;
    @Column(name = "payload_characters", nullable = false) long payloadCharacters;
    @Column(name = "current_node", length = 320) String currentNode;
    @Version @Column(name = "row_version", nullable = false) long version;
    protected AnalysisContinuationRun() { }
}
