package com.taxonomy.workspace.model;

import jakarta.persistence.*;

import java.time.Instant;
import com.taxonomy.workspace.service.WorkspaceManager;

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
}
