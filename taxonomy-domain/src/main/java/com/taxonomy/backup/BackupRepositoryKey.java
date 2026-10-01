package com.taxonomy.backup;

/** Logical repository selection. A null workspace identifies the central repository only. */
public record BackupRepositoryKey(String repositoryId, String workspaceId) {
    public BackupRepositoryKey {
        BackupChecks.text(repositoryId, "repositoryId");
        if (workspaceId != null) BackupChecks.text(workspaceId, "workspaceId");
    }
}
