package com.taxonomy.backup;

import com.taxonomy.identity.StableIdentityHash;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Server-supplied routing identity for an interoperability journal, distinct from its workspace overlay key. */
public record BackupIntegrationScope(BackupRepositoryKey repository, String branch) {
    public BackupIntegrationScope {
        Objects.requireNonNull(repository);
        if (repository.workspaceId() == null) throw new IllegalArgumentException("Integration journals require a workspace");
        BackupChecks.text(branch, "integration branch");
    }
    public String scopeId() {
        return StableIdentityHash.sha256(repository.repositoryId() + "\u0000" + repository.workspaceId() + "\u0000" + branch);
    }

    @FunctionalInterface
    public interface Selector {
        List<BackupIntegrationScope> select(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException;
    }
}
