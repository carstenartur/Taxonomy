package com.taxonomy.relations.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Completion token for one fully rebuilt repository/workspace/branch relation projection.
 *
 * <p>A checkpoint is valid only while its authoritative commit remains the exact
 * selected branch head. Incremental command projections invalidate the checkpoint;
 * user-facing reads may consume decision rows only after a complete rebuild has
 * atomically replaced the branch rows and checkpoint in one database transaction.</p>
 */
@Entity
@Table(name = "relation_decision_projection_checkpoint",
        indexes = {
                @Index(
                        name = "idx_rel_projection_checkpoint_repository",
                        columnList = "repository_id"),
                @Index(
                        name = "idx_rel_projection_checkpoint_scope",
                        columnList = "repository_id, workspace_scope_key, branch")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_rel_projection_checkpoint_scope",
                columnNames = {
                        "repository_id",
                        "workspace_scope_key",
                        "branch"
                }))
public class RelationDecisionProjectionCheckpoint {

    public static final String CENTRAL_SCOPE_KEY = "__shared__";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false, length = 255)
    private String repositoryId;

    @Column(name = "workspace_id", length = 255)
    private String workspaceId;

    @Column(name = "workspace_scope_key", nullable = false, length = 255)
    private String workspaceScopeKey = CENTRAL_SCOPE_KEY;

    @Column(name = "branch", nullable = false, length = 255)
    private String branch;

    @Column(name = "authoritative_commit_id", nullable = false, length = 40)
    private String authoritativeCommitId;

    @Column(name = "relation_count", nullable = false)
    private int relationCount;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        synchronize();
        if (completedAt == null) {
            completedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        synchronize();
        completedAt = Instant.now();
    }

    private void synchronize() {
        repositoryId = requireText(repositoryId, "repositoryId");
        workspaceId = normalizeOptional(workspaceId);
        workspaceScopeKey = scopeKeyFor(workspaceId);
        branch = requireText(branch, "branch");
        authoritativeCommitId = requireCommitId(authoritativeCommitId);
        if (relationCount < 0) {
            throw new IllegalArgumentException("relationCount must not be negative");
        }
    }

    public static String scopeKeyFor(String workspaceId) {
        String normalized = normalizeOptional(workspaceId);
        return normalized == null ? CENTRAL_SCOPE_KEY : normalized;
    }

    public Long getId() {
        return id;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = requireText(repositoryId, "repositoryId");
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = normalizeOptional(workspaceId);
        this.workspaceScopeKey = scopeKeyFor(this.workspaceId);
    }

    public String getWorkspaceScopeKey() {
        return workspaceScopeKey;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = requireText(branch, "branch");
    }

    public String getAuthoritativeCommitId() {
        return authoritativeCommitId;
    }

    public void setAuthoritativeCommitId(String authoritativeCommitId) {
        this.authoritativeCommitId = requireCommitId(authoritativeCommitId);
    }

    public int getRelationCount() {
        return relationCount;
    }

    public void setRelationCount(int relationCount) {
        if (relationCount < 0) {
            throw new IllegalArgumentException("relationCount must not be negative");
        }
        this.relationCount = relationCount;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }

    private static String requireCommitId(String value) {
        String normalized = requireText(value, "authoritativeCommitId");
        if (normalized.length() != 40
                || !normalized.chars().allMatch(RelationDecisionProjectionCheckpoint::isHex)) {
            throw new IllegalArgumentException(
                    "authoritativeCommitId must be a full Git object ID");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isHex(int value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
