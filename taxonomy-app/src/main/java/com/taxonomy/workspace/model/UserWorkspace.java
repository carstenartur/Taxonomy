package com.taxonomy.workspace.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Persistent workspace metadata for a user.
 *
 * <p>Each user has at least one workspace that provides an isolated context
 * for architecture editing. The workspace tracks which branch the user is on,
 * their base branch, and timestamps for auditing. The actual navigation state
 * (context stack, projection tracking) is held in-memory by the
 * {@code WorkspaceManager}.
 *
 * <p>The shared integration workspace ({@code shared = true}) represents
 * the canonical team-wide repository state and is not owned by any single user.
 */
@Entity
@Table(name = "user_workspace", indexes = {
    @Index(name = "idx_workspace_username", columnList = "username"),
    @Index(name = "idx_workspace_shared", columnList = "shared")
})
public class UserWorkspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace_id", nullable = false, unique = true)
    private String workspaceId;

    @Column(nullable = false)
    private String username;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "current_branch", nullable = false)
    private String currentBranch = "draft";

    @Column(name = "base_branch")
    private String baseBranch = "draft";

    @Column(nullable = false)
    private boolean shared = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_status", nullable = false)
    private WorkspaceProvisioningStatus provisioningStatus = WorkspaceProvisioningStatus.READY;

    @Enumerated(EnumType.STRING)
    @Column(name = "topology_mode", nullable = false)
    private RepositoryTopologyMode topologyMode = RepositoryTopologyMode.INTERNAL_SHARED;

    @Column(name = "source_repository_id")
    private String sourceRepositoryId;

    @Column(name = "base_commit")
    private String baseCommit;

    @Column(name = "current_commit")
    private String currentCommit;

    @Column(name = "sync_target_branch")
    private String syncTargetBranch;

    @Column(name = "provisioned_at")
    private Instant provisionedAt;

    @Column(name = "provisioning_error")
    private String provisioningError;

    public UserWorkspace() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCurrentBranch() {
        return currentBranch;
    }

    public void setCurrentBranch(String currentBranch) {
        this.currentBranch = currentBranch;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    public WorkspaceProvisioningStatus getProvisioningStatus() {
        return provisioningStatus;
    }

    public void setProvisioningStatus(WorkspaceProvisioningStatus provisioningStatus) {
        this.provisioningStatus = provisioningStatus;
    }

    public RepositoryTopologyMode getTopologyMode() {
        return topologyMode;
    }

    public void setTopologyMode(RepositoryTopologyMode topologyMode) {
        this.topologyMode = topologyMode;
    }

    public String getSourceRepositoryId() {
        return sourceRepositoryId;
    }

    public void setSourceRepositoryId(String sourceRepositoryId) {
        this.sourceRepositoryId = sourceRepositoryId;
    }

    public String getBaseCommit() {
        return baseCommit;
    }

    public void setBaseCommit(String baseCommit) {
        this.baseCommit = baseCommit;
    }

    public String getCurrentCommit() {
        return currentCommit;
    }

    public void setCurrentCommit(String currentCommit) {
        this.currentCommit = currentCommit;
    }

    public String getSyncTargetBranch() {
        return syncTargetBranch;
    }

    public void setSyncTargetBranch(String syncTargetBranch) {
        this.syncTargetBranch = syncTargetBranch;
    }

    public Instant getProvisionedAt() {
        return provisionedAt;
    }

    public void setProvisionedAt(Instant provisionedAt) {
        this.provisionedAt = provisionedAt;
    }

    public String getProvisioningError() {
        return provisioningError;
    }

    public void setProvisioningError(String provisioningError) {
        this.provisioningError = provisioningError;
    }
}
