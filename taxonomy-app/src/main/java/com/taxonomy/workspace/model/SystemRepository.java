package com.taxonomy.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Catalog entry for a central architecture repository.
 *
 * <p>The historic class name is retained as a compatibility boundary while the
 * application evolves from one implicit system repository to an explicit
 * multi-repository catalog. Every entry owns a distinct logical JGit storage
 * name. The repository marked {@link #isPrimaryRepo()} keeps the legacy
 * {@code taxonomy-dsl} storage name.</p>
 *
 * <p>Credentials are deliberately not part of the public entity contract. The
 * legacy plaintext column remains mapped only so startup migration can erase
 * values written by older releases. New credentials are supplied exclusively
 * through deployment secret configuration.</p>
 */
@Entity
@Table(name = "system_repository")
public class SystemRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false, unique = true)
    private String repositoryId;

    @Column(name = "storage_repository_name", unique = true)
    private String storageRepositoryName;

    @Column(name = "slug", unique = true)
    private String slug;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility")
    private RepositoryVisibility visibility = RepositoryVisibility.PRIVATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state")
    private RepositoryLifecycleState lifecycleState = RepositoryLifecycleState.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type")
    private RepositoryOwnerType ownerType = RepositoryOwnerType.USER;

    @Column(name = "owner_id")
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "topology_mode", nullable = false)
    private RepositoryTopologyMode topologyMode;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "draft";

    @Column(name = "external_url")
    private String externalUrl;

    /** Legacy cleanup-only mapping. Never expose or use this value for authentication. */
    @Column(name = "external_auth_token")
    private String legacyExternalAuthToken;

    @Column(name = "upstream_repository_id")
    private String upstreamRepositoryId;

    @Column(name = "upstream_branch")
    private String upstreamBranch;

    @Column(name = "fork_point_commit")
    private String forkPointCommit;

    @Column(name = "last_fetch_at")
    private Instant lastFetchAt;

    @Column(name = "last_push_at")
    private Instant lastPushAt;

    @Column(name = "last_fetch_commit")
    private String lastFetchCommit;

    @Column(name = "primary_repo", nullable = false)
    private boolean primaryRepo;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    public SystemRepository() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(String repositoryId) {
        this.repositoryId = repositoryId;
    }

    public String getStorageRepositoryName() {
        return storageRepositoryName;
    }

    public void setStorageRepositoryName(String storageRepositoryName) {
        this.storageRepositoryName = storageRepositoryName;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public RepositoryVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(RepositoryVisibility visibility) {
        this.visibility = visibility;
    }

    public RepositoryLifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(RepositoryLifecycleState lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public RepositoryOwnerType getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(RepositoryOwnerType ownerType) {
        this.ownerType = ownerType;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public RepositoryTopologyMode getTopologyMode() {
        return topologyMode;
    }

    public void setTopologyMode(RepositoryTopologyMode topologyMode) {
        this.topologyMode = topologyMode;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    /** Whether an older release left a plaintext credential that must be erased. */
    public boolean hasLegacyPlaintextCredential() {
        return legacyExternalAuthToken != null && !legacyExternalAuthToken.isBlank();
    }

    /** Clear a legacy plaintext value without ever returning it to application code. */
    public void clearLegacyPlaintextCredential() {
        legacyExternalAuthToken = null;
    }

    public String getUpstreamRepositoryId() {
        return upstreamRepositoryId;
    }

    public void setUpstreamRepositoryId(String upstreamRepositoryId) {
        this.upstreamRepositoryId = upstreamRepositoryId;
    }

    public String getUpstreamBranch() {
        return upstreamBranch;
    }

    public void setUpstreamBranch(String upstreamBranch) {
        this.upstreamBranch = upstreamBranch;
    }

    public String getForkPointCommit() {
        return forkPointCommit;
    }

    public void setForkPointCommit(String forkPointCommit) {
        this.forkPointCommit = forkPointCommit;
    }

    public Instant getLastFetchAt() {
        return lastFetchAt;
    }

    public void setLastFetchAt(Instant lastFetchAt) {
        this.lastFetchAt = lastFetchAt;
    }

    public Instant getLastPushAt() {
        return lastPushAt;
    }

    public void setLastPushAt(Instant lastPushAt) {
        this.lastPushAt = lastPushAt;
    }

    public String getLastFetchCommit() {
        return lastFetchCommit;
    }

    public void setLastFetchCommit(String lastFetchCommit) {
        this.lastFetchCommit = lastFetchCommit;
    }

    public boolean isPrimaryRepo() {
        return primaryRepo;
    }

    public void setPrimaryRepo(boolean primaryRepo) {
        this.primaryRepo = primaryRepo;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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

    public long getVersion() {
        return version;
    }
}
