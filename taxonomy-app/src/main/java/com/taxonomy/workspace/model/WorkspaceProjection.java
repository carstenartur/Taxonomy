package com.taxonomy.workspace.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Tracks per-user database projection state.
 *
 * <p>A projection is a materialized view of the DSL at a specific commit,
 * allowing each user to have their own independent projection of the
 * architecture model. This entity records which commit the projection was
 * built from and when it was last materialized, as well as the corresponding
 * search-index state.
 *
 * <p>When the underlying branch advances past the projection commit, the
 * {@code stale} flag is set so that downstream consumers know the
 * projection needs to be rebuilt.
 */
@Entity
@Table(name = "workspace_projection", indexes = {
    @Index(name = "idx_projection_username", columnList = "username"),
    @Index(name = "idx_projection_workspace", columnList = "workspace_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_projection_username", columnNames = "username")
})
public class WorkspaceProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "workspace_id", nullable = false, unique = true)
    private String workspaceId;

    @Column(name = "projection_commit_id")
    private String projectionCommitId;

    @Column(name = "projection_branch")
    private String projectionBranch;

    @Column(name = "projection_timestamp")
    private Instant projectionTimestamp;

    @Column(name = "index_commit_id")
    private String indexCommitId;

    @Column(name = "index_timestamp")
    private Instant indexTimestamp;

    @Column(nullable = false)
    private boolean stale = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public WorkspaceProjection() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getProjectionCommitId() {
        return projectionCommitId;
    }

    public void setProjectionCommitId(String projectionCommitId) {
        this.projectionCommitId = projectionCommitId;
    }

    public String getProjectionBranch() {
        return projectionBranch;
    }

    public void setProjectionBranch(String projectionBranch) {
        this.projectionBranch = projectionBranch;
    }

    public Instant getProjectionTimestamp() {
        return projectionTimestamp;
    }

    public void setProjectionTimestamp(Instant projectionTimestamp) {
        this.projectionTimestamp = projectionTimestamp;
    }

    public String getIndexCommitId() {
        return indexCommitId;
    }

    public void setIndexCommitId(String indexCommitId) {
        this.indexCommitId = indexCommitId;
    }

    public Instant getIndexTimestamp() {
        return indexTimestamp;
    }

    public void setIndexTimestamp(Instant indexTimestamp) {
        this.indexTimestamp = indexTimestamp;
    }

    public boolean isStale() {
        return stale;
    }

    public void setStale(boolean stale) {
        this.stale = stale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
