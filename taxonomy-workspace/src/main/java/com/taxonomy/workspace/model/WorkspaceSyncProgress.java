package com.taxonomy.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;

/** Stored observations shared by current branch progress and historical username-wide evidence. */
@MappedSuperclass
public abstract class WorkspaceSyncProgress {
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

    protected void copyProgressTo(WorkspaceSyncProgress target) {
        target.lastSyncedCommitId = lastSyncedCommitId;
        target.lastSyncTimestamp = lastSyncTimestamp;
        target.lastPublishedCommitId = lastPublishedCommitId;
        target.lastPublishTimestamp = lastPublishTimestamp;
        target.syncStatus = syncStatus;
        target.unpublishedCommitCount = unpublishedCommitCount;
        target.createdAt = createdAt;
        target.updatedAt = updatedAt;
    }
}
