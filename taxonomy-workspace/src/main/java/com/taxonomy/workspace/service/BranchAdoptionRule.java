package com.taxonomy.workspace.service;

import com.taxonomy.identity.StableIdentityHash;
import jakarta.persistence.*;
import java.util.Set;

/** Durable opt-in and cursor for exactly one repository/workspace/branch. */
@Entity
@Table(name = "branch_adoption_rule", indexes = @Index(name = "ix_branch_adoption_due", columnList = "enabled,next_check"))
public class BranchAdoptionRule {
    @Id
    @Column(length = 64)
    String id;
    @Column(name = "repository_id", nullable = false)
    String repositoryId;
    @Column(name = "workspace_id")
    String workspaceId;
    @Column(name = "target_branch", nullable = false)
    String branch;
    @Column(name = "source_branch", nullable = false)
    String sourceBranch;
    @Column(name = "actor_scope", nullable = false)
    String username;
    @Column(name = "principal_id", length = 64, nullable = false)
    String principalId;
    @Column(name = "login_kind", length = 12, nullable = false)
    String loginKind;
    @Column(name = "binding_key", length = 64, nullable = false)
    String bindingKey;
    @Column(nullable = false)
    boolean enabled;
    @Column(nullable = false)
    long revision;
    @Column(name = "next_check", nullable = false)
    long nextCheck;
    @Column(nullable = false, length = 24)
    String status;
    @Column(length = 255)
    String detail;
    @Column(name = "source_cursor", length = 40)
    String cursor;
    @Column(name = "source_commit", length = 40)
    String sourceCommit;
    @Column(name = "source_tip", length = 40)
    String sourceTip;
    @Column(name = "target_before", length = 40)
    String targetBefore;
    @Column(name = "target_after", length = 40)
    String targetAfter;
    @Column(name = "prepared_commit", length = 40)
    String prepared;

    protected BranchAdoptionRule() {
    }

    BranchAdoptionRule(RepositoryContext selected, String sourceBranch) {
        id = key(selected);
        repositoryId = selected.repositoryId();
        workspaceId = selected.workspaceId();
        branch = selected.branch();
        this.sourceBranch = sourceBranch;
        status = "DISABLED";
    }

    public static String key(RepositoryContext c) {
        return StableIdentityHash.sha256(c.repositoryId().length() + ":" + c.repositoryId()
                + (c.workspaceId() == null ? "-:" : c.workspaceId().length() + ":" + c.workspaceId()) + c.branch());
    }

    WorkspaceFetchAuthority.Grant grant() {
        return new WorkspaceFetchAuthority.Grant(principalId, loginKind, bindingKey);
    }

    void bind(String actor, WorkspaceFetchAuthority.Grant grant) {
        username = actor;
        principalId = grant.principalId();
        loginKind = grant.loginKind();
        bindingKey = grant.bindingKey();
    }

    RepositoryContext context() {
        return workspaceId == null ? RepositoryContext.centralWrite(repositoryId, branch, username)
                : RepositoryContext.workspace(repositoryId, workspaceId, branch, username);
    }

    public record View(String repositoryId, String workspaceId, String branch, String sourceRepositoryId,
            String sourceBranch,
            boolean enabled, long revision, String status, String sourceCommit, String targetBefore,
            String targetAfter, String detail) {
    }

    View view() {
        return new View(repositoryId, workspaceId, branch, repositoryId, sourceBranch, enabled, revision, status,
                sourceCommit, targetBefore, targetAfter, detail);
    }

    boolean stopped() {
        return Set.of("CONFLICT", "PREREQUISITE", "AUTHORIZATION").contains(status);
    }
}
