package com.taxonomy.workspace.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Represents the system-owned central repository configuration.
 *
 * <p>Exactly one primary system repository exists at any time. It defines
 * the shared integration branch and topology mode used by all user workspaces.
 * The system repository is automatically created at application startup by
 * {@code SystemRepositoryService} if no primary repository record exists.
 *
 * <p>In {@code INTERNAL_SHARED} mode, the application hosts the shared
 * repository internally. In {@code EXTERNAL_CANONICAL} mode, an external
 * Git repository URL is configured as the canonical source.
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

    @Column(name = "primary_repo", nullable = false)
    private boolean primaryRepo = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SystemRepository() {
    }

    // ── Getters / Setters ───────────────────────────────────────────

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
