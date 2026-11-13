package com.taxonomy.versioning.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Maps the JDBC journal for Hibernate-managed dialects and validated restore schemas. */
@Entity
@Table(name = "history_index_operation", indexes = {
        @Index(name = "ix_hist_index_due", columnList = "phase,due_at"),
        @Index(name = "ix_hist_index_scope", columnList = "repository_id,workspace_id")})
public class HistoryIndexOperationRow {
    @Id
    @Column(name = "operation_id", length = 64, nullable = false)
    String operationId;

    @Column(name = "command_kind", length = 12, nullable = false)
    String commandKind;

    @Column(name = "cause_id", length = 64, nullable = false)
    String causeId;

    @Column(name = "actor_id", length = 64, nullable = false)
    String actorId;

    @Column(name = "actor_scope", length = 255, nullable = false)
    String actorScope;

    @Column(name = "login_kind", length = 12, nullable = false)
    String loginKind;

    @Column(name = "binding_key", length = 64, nullable = false)
    String bindingKey;

    @Column(name = "repository_id", length = 255, nullable = false)
    String repositoryId;

    @Column(name = "workspace_id", length = 255)
    String workspaceId;

    @Column(name = "workspace_owner", length = 255)
    String workspaceOwner;

    @Column(name = "repository_scope", length = 24, nullable = false)
    String repositoryScope;

    @Column(name = "collaborative", nullable = false)
    boolean collaborative;

    @Column(name = "branch_name", length = 255, nullable = false)
    String branchName;

    @Column(name = "captured_commit", length = 40)
    String capturedCommit;

    @Column(name = "phase", length = 24, nullable = false)
    String phase;

    @Column(name = "attempts", nullable = false)
    int attempts;

    @Column(name = "result_count")
    Integer resultCount;

    @Column(name = "executor_id", length = 64)
    String executorId;

    @Column(name = "lease_id", length = 36)
    String leaseId;

    @Column(name = "created_at", nullable = false)
    long createdAt;

    @Column(name = "attempted_at")
    Long attemptedAt;

    @Column(name = "due_at")
    Long dueAt;

    @Column(name = "completed_at")
    Long completedAt;

    @Column(name = "archived_at")
    Long archivedAt;

    @Column(name = "reason_code", length = 40)
    String reasonCode;
}
