package com.taxonomy.workspace.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Objects;

/** Recovery evidence for publishing one private working copy to its recorded central branch. */
@Entity
@Table(name = "workspace_publication_operation", indexes = {
        @Index(name = "ix_ws_publish_due", columnList = "phase,next_attempt_at"),
        @Index(name = "ix_ws_publish_archive", columnList = "archived_at,completed_at")})
public class WorkspacePublicationOperation extends LeasedRepositoryOperation {
    enum Step { PREPARE, CANDIDATES, SOURCE_REF, WORKSPACE_REF, TRACKING_REF, CENTRAL_PORTFOLIO, LOCAL_PORTFOLIO,
        WORKSPACE_METADATA, SOURCE_METADATA, PROGRESS, COMPLETE }
    enum Status { ADMITTED, RUNNING, RETRYABLE, AWAITING_AUTHORIZATION, COMPLETED, SUPERSEDED, MANUAL_REVIEW, DENIED }

    @Column(name = "causation_id", nullable = false, length = 64) String cause;
    @Column(name = "actor_id", nullable = false, length = 64) String actor;
    @Column(name = "actor_scope", nullable = false) String username;
    @Column(name = "login_kind", nullable = false, length = 12) String loginKind;
    @Column(name = "binding_key", nullable = false, length = 64) String binding;
    @Column(name = "policy_code", nullable = false, length = 48) String policy;
    @Column(name = "repository_id", nullable = false) String repository;
    @Column(name = "workspace_id", nullable = false) String workspace;
    @Column(name = "local_branch", nullable = false) String localBranch;
    @Column(name = "source_branch", nullable = false) String sourceBranch;
    @Column(name = "keep_workspace", nullable = false) boolean keepWorkspace;
    @Column(name = "fork_point", length = 40) String forkPoint;
    @Column(name = "input_source", length = 40) String inputSource;
    @Column(name = "input_local", length = 40) String inputLocal;
    @Column(name = "input_tracking", length = 40) String inputTracking;
    @Column(name = "expected_local", length = 40) String expectedLocal;
    @Column(name = "expected_tracking", length = 40) String expectedTracking;
    @Column(name = "merged_digest", length = 64) String mergedDigest;
    @Column(name = "prepared_source", length = 40) String preparedSource;
    @Column(name = "prepared_local", length = 40) String preparedLocal;
    @Column(name = "prepared_tracking", length = 40) String preparedTracking;
    @Enumerated(EnumType.STRING) @Column(name = "operation_step", nullable = false, length = 28) Step step;
    @Column(name = "created_at", nullable = false) long createdAt;
    @Column(name = "published_at") Long publishedAt;
    @Column(name = "archived_by", length = 64) String archivedBy;

    protected WorkspacePublicationOperation() { }

    RepositoryContext context() { return RepositoryContext.workspace(repository, workspace, localBranch, username); }

    boolean belongsTo(RepositoryContext selected, String stableActor) {
        return selected.scope() == RepositoryScope.WORKSPACE && Objects.equals(actor,stableActor)
                && Objects.equals(username,selected.username()) && Objects.equals(workspace,selected.workspaceId())
                && Objects.equals(repository,selected.repositoryId()) && Objects.equals(localBranch,selected.branch());
    }
}
