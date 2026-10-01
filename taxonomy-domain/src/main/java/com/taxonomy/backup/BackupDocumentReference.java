package com.taxonomy.backup;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Immutable content evidence from the authorized Git inventory, never a branch-name tenant lookup. */
public record BackupDocumentReference(BackupRepositoryKey repository, String commit, String path, String sha256) {
    public BackupDocumentReference {
        Objects.requireNonNull(repository);
        BackupChecks.hash(commit, 40, "document commit");
        PortableGitPaths.requireFile(path);
        BackupChecks.hash(sha256, 64, "document content checksum");
    }

    /**
     * Supplied by capture composition from verified, immutable Git objects in the selected closure.
     * Current capture must omit superseded committed documents when a saved working copy differs.
     * Historical document commits must be included in the captured required-commit inventory.
     */
    @FunctionalInterface public interface Selector {
        List<BackupDocumentReference> select(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException;
    }
}
