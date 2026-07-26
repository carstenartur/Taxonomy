package com.taxonomy.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Represents the system-owned central repository configuration.
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

    @Column(name = "display_name")
    private String displayName;

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

    @Column(name = "last_fetch_at")
    private Instant lastFetchAt;

    @Column(name = "last_push_at")
    private Instant lastPushAt;

    @Column(name = "last_fetch_commit")
    private String lastFetchCommit;

    @Column(name = "primary_repo", nullable = false)
    private boolean primaryRepo = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
