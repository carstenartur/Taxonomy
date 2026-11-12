package com.taxonomy.workspace.model;

import jakarta.persistence.*;

/**
 * Tracks the synchronization state between a user's workspace and the
 * shared integration repository.
 *
 * <p>This entity records the last commit that was pulled from the shared
 * repository ({@code lastSyncedCommitId}) and the last commit the user
 * published back ({@code lastPublishedCommitId}), together with their
 * timestamps.
 *
 * <p>The {@code syncStatus} field summarises the relationship between the
 * user's workspace and the shared repo:
 * <ul>
 *   <li>{@code UP_TO_DATE} — both sides are at the same state</li>
 *   <li>{@code BEHIND} — the shared repo has commits the user has not pulled</li>
 *   <li>{@code AHEAD} — the user has unpublished local commits</li>
 *   <li>{@code DIVERGED} — both sides have diverged</li>
 * </ul>
 */
@Entity
@Table(name = "sync_state", uniqueConstraints = {
    @UniqueConstraint(name = "uq_sync_username", columnNames = "username"),
    @UniqueConstraint(name = "uq_sync_workspace", columnNames = "workspace_id")
})
public class SyncState extends WorkspaceSyncProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "workspace_id", nullable = false)
    private String workspaceId;

    @Transient
    private WorkspaceSyncScope observationScope;

    public WorkspaceSyncScope getObservationScope() { return observationScope; }
    void setObservationScope(WorkspaceSyncScope scope) { observationScope = scope; }

    public SyncState() {
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

}
