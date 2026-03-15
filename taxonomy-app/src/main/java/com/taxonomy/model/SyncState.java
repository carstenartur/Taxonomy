package com.taxonomy.model;

import jakarta.persistence.*;

import java.time.Instant;

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
@Table(name = "sync_state", indexes = {
    @Index(name = "idx_sync_username", columnList = "username"),
    @Index(name = "idx_sync_workspace", columnList = "workspace_id")
})
public class SyncState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(name = "workspace_id", nullable = false, unique = true)
    private String workspaceId;

    @Column(name = "last_synced_commit_id")
    private String lastSyncedCommitId;

    @Column(name = "last_sync_timestamp")
    private Instant lastSyncTimestamp;

    @Column(name = "last_published_commit_id")
    private String lastPublishedCommitId;

    @Column(name = "last_publish_timestamp")
    private Instant lastPublishTimestamp;

    @Column(name = "sync_status", nullable = false)
    private String syncStatus = "UP_TO_DATE";

    @Column(name = "unpublished_commit_count", nullable = false)
    private int unpublishedCommitCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

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

    public String getLastSyncedCommitId() {
        return lastSyncedCommitId;
    }

    public void setLastSyncedCommitId(String lastSyncedCommitId) {
        this.lastSyncedCommitId = lastSyncedCommitId;
    }

    public Instant getLastSyncTimestamp() {
        return lastSyncTimestamp;
    }

    public void setLastSyncTimestamp(Instant lastSyncTimestamp) {
        this.lastSyncTimestamp = lastSyncTimestamp;
    }

    public String getLastPublishedCommitId() {
        return lastPublishedCommitId;
    }

    public void setLastPublishedCommitId(String lastPublishedCommitId) {
        this.lastPublishedCommitId = lastPublishedCommitId;
    }

    public Instant getLastPublishTimestamp() {
        return lastPublishTimestamp;
    }

    public void setLastPublishTimestamp(Instant lastPublishTimestamp) {
        this.lastPublishTimestamp = lastPublishTimestamp;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public int getUnpublishedCommitCount() {
        return unpublishedCommitCount;
    }

    public void setUnpublishedCommitCount(int unpublishedCommitCount) {
        this.unpublishedCommitCount = unpublishedCommitCount;
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
