package com.taxonomy.backup.jobs;

import com.taxonomy.backup.*;
import java.time.Instant;
import java.util.UUID;

/** Internal durable state; web boundaries use a separate allowlisted view. */
public record BackupJob(BackupJobId id, AuthorizedBackupRequest authorization, BackupJobState state,
                        Instant createdAt, Instant updatedAt, UUID owner, int attempt, Instant leaseUntil,
                        long progressBytes, boolean cancellationRequested, BackupArtifact artifact,
                        Instant expiresAt, BackupJobFailure failure) {
    public boolean heavy() { return authorization.request().profile().includesHistory() || authorization.request().profile().isInstallation(); }
}
