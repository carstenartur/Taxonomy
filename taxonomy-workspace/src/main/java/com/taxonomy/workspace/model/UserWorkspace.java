package com.taxonomy.workspace.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Persistent workspace metadata for a user.
 *
 * <p>Each user has at least one workspace that provides an isolated context
 * for architecture editing. Every provisioned workspace records an explicit
 * source repository and source branch so synchronization never has to infer a
 * global central repository.</p>
 */
@Entity
@Table(name = "user_workspace", indexes = {
    @Index(name = "idx_workspace_username", columnList = "username"),
    @Index(name = "idx_workspace_shared", columnList = "shared"),
    @Index(name = "idx_workspace_source_repository", columnList = "source_repository_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_workspace_user_name", columnNames = {"username", "display_name"})
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

    /** Explicit opt-in for repository contributors; unrelated to the legacy central shared workspace. */
    // Ordinary metadata merges must not replay a stale sharing decision.
    // WorkspaceCollaborationService owns updates through its locked repository operation.
    @Column(updatable = false)
    private Boolean collaborative = false;

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

    @Column(name = "source_branch")
    private String sourceBranch;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type")
    private WorkspaceRelationshipType relationshipType = WorkspaceRelationshipType.WORKING_COPY;

    @Column(name = "base_commit")
    private String baseCommit;

    @Column(name = "current_commit")
    private String currentCommit;

    @Column(name = "last_fetched_commit")
    private String lastFetchedCommit;

    @Column(name = "last_integrated_commit")
    private String lastIntegratedCommit;

    @Column(name = "sync_target_branch")
    private String syncTargetBranch;

    @Column(name = "provisioned_at")
    private Instant provisionedAt;

    // The bulk claim owns these fields. Ordinary detached metadata saves must not resurrect an old lease.
    @Column(name = "provisioning_lease_owner", length = 36, updatable = false)
    private String provisioningLeaseOwner;

    @Column(name = "provisioning_lease_until", updatable = false)
    private Instant provisioningLeaseUntil;

    @ColumnDefault("0")
    @Column(name = "provisioning_attempt", nullable = false, updatable = false)
    private int provisioningAttempt;

    @Column(name = "provisioning_attempt_at", updatable = false)
    private Instant provisioningAttemptAt;

    @Column(name = "provisioning_sync_base_branch", updatable = false)
    private String provisioningSyncBaseBranch;

    @Column(name = "provisioning_error")
    private String provisioningError;

    @Column(name = "description", length = 500)
    private String description;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    public UserWorkspace() {
    }

    /** First allocation of a newly inserted working copy; retries use the atomic repository claim. */
    public void startProvisioningLease(String owner, Instant startedAt, Instant expiresAt) {
        provisioningLeaseOwner = owner;
        provisioningLeaseUntil = expiresAt;
        provisioningAttemptAt = startedAt;
        provisioningAttempt = 1;
    }

    public String getProvisioningSyncBaseBranch() { return provisioningSyncBaseBranch; }
    public void setProvisioningSyncBaseBranch(String branch) { provisioningSyncBaseBranch = branch; }

    @JsonIgnore
    public String getProvisioningLeaseOwner() { return provisioningLeaseOwner; }

    public Instant getProvisioningLeaseUntil() { return provisioningLeaseUntil; }
    public int getProvisioningAttempt() { return provisioningAttempt; }
    public Instant getProvisioningAttemptAt() { return provisioningAttemptAt; }

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

    public boolean isCollaborative() { return Boolean.TRUE.equals(collaborative); }

    /** Initializes a new row; persisted sharing changes belong to WorkspaceCollaborationService. */
    public void setCollaborative(boolean collaborative) { this.collaborative = collaborative; }

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

    public String getSourceBranch() {
        return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
        this.sourceBranch = sourceBranch;
    }

    public WorkspaceRelationshipType getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(WorkspaceRelationshipType relationshipType) {
        this.relationshipType = relationshipType;
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

    public String getLastFetchedCommit() {
        return lastFetchedCommit;
    }

    public void setLastFetchedCommit(String lastFetchedCommit) {
        this.lastFetchedCommit = lastFetchedCommit;
    }

    public String getLastIntegratedCommit() {
        return lastIntegratedCommit;
    }

    public void setLastIntegratedCommit(String lastIntegratedCommit) {
        this.lastIntegratedCommit = lastIntegratedCommit;
    }

    /** The same upstream selection applies to standalone fetch, pull, and publication. */
    public String resolveUpstreamBranch(SystemRepository sourceRepository) {
        if (syncTargetBranch != null && !syncTargetBranch.isBlank()) return syncTargetBranch;
        if (sourceBranch != null && !sourceBranch.isBlank()) return sourceBranch;
        return sourceRepository.getDefaultBranch();
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }
}
