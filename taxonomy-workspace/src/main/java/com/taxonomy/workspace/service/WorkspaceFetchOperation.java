package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Durable authority for fetching an exact Git commit into one working copy. */
@Entity
@Table(name = "workspace_fetch_operation", indexes = @Index(name = "ix_ws_fetch_recovery", columnList = "phase,next_attempt_at"))
public class WorkspaceFetchOperation {
    @Id @Column(name = "operation_id", length = 64) String id;
    @Column(name = "fingerprint", nullable = false, length = 64) String fingerprint;
    @Column(name = "cause_id", nullable = false, length = 64) String causeId;
    @Column(name = "actor_id", nullable = false, length = 36) String actor;
    @Column(name = "login_kind", nullable = false, length = 12) String loginKind;
    @Column(name = "binding_key", nullable = false, length = 64) String bindingKey;
    @Column(name = "policy_code", nullable = false, length = 40) String policy;
    @Column(name = "actor_scope", nullable = false, length = 255) String username;
    @Column(name = "repository_id", nullable = false, length = 255) String repositoryId;
    @Column(name = "workspace_id", nullable = false, length = 255) String workspaceId;
    @Column(name = "local_branch", nullable = false, length = 255) String branch;
    @Column(name = "source_branch", nullable = false, length = 255) String sourceBranch;
    @Column(name = "requested_commit", nullable = false, length = 40) String requested;
    @Column(name = "expected_commit", length = 40) String expected;
    @Column(name = "observed_commit", length = 40) String observed;
    @Column(name = "result_commit", length = 40) String result;
    @Column(name = "phase", nullable = false, length = 24) String phase;
    @Column(name = "attempts", nullable = false) int attempts;
    @Column(name = "created_at", nullable = false) long createdAt;
    @Column(name = "last_attempt_at") Long lastAttemptAt;
    @Column(name = "next_attempt_at") Long nextAttemptAt;
    @Column(name = "completed_at") Long completedAt;
    @Column(name = "executor_id", length = 64) String executor;
    @Column(name = "lease_id", length = 36) String lease;
    @Column(name = "decision_code", length = 40) String decision;

    protected WorkspaceFetchOperation() { }

    RepositoryContext context() {
        return RepositoryContext.workspace(repositoryId, workspaceId, branch, username);
    }

    boolean ownedBy(RepositoryContext selected, String stableActor) {
        return selected.scope() == RepositoryScope.WORKSPACE && actor.equals(stableActor)
                && username.equals(selected.username()) && repositoryId.equals(selected.repositoryId())
                && workspaceId.equals(selected.workspaceId()) && branch.equals(selected.branch());
    }
}
