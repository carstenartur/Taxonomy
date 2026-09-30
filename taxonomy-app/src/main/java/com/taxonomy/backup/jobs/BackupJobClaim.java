package com.taxonomy.backup.jobs;

import java.util.Objects;
import java.util.UUID;

/** Internal fencing token. It is never sent to the browser. */
public record BackupJobClaim(BackupJob job, UUID owner, int attempt) {
    public BackupJobClaim { Objects.requireNonNull(job); Objects.requireNonNull(owner); if (attempt < 1) throw new IllegalArgumentException("Invalid claim"); }
    public String artifactName() { return job.id().value() + "-" + owner + "-" + attempt + ".taxbackup"; }
}
