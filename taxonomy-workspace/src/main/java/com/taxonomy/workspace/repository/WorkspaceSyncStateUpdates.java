package com.taxonomy.workspace.repository;

import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.model.WorkspaceSyncScope;

import java.time.Instant;

public interface WorkspaceSyncStateUpdates {
    SyncState readScope(WorkspaceSyncScope scope);
    void recordSync(WorkspaceSyncScope scope, String commit, Instant occurredAt, boolean published);
    void recordLocalChanges(WorkspaceSyncScope scope, int ahead, int behind, Instant occurredAt);
}
