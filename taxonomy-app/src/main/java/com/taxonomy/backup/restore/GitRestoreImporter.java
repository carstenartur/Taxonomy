package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import java.io.IOException;
import java.util.*;

/** Host adapter delegates into workspace-owned import using the explicit isolated target. */
@FunctionalInterface
public interface GitRestoreImporter {
    Result importGit(VerifiedBackup verified,
            Map<BackupRepositoryKey, RestoreRepositoryAllocation> approvedAllocations,
            RestoreTargetGeneration target, BackupCheckpoint checkpoint) throws IOException;

    /** Source keys identify declared archive repositories, never physical target destinations. */
    record Result(Map<BackupRepositoryKey, RepositoryProof> repositories) {
        public Result {
            repositories = Map.copyOf(repositories);
            if (repositories.size() > BackupLimits.MAX_ITEMS) throw new IllegalArgumentException("Too many Git restore proofs");
        }
    }

    /** Restored state contains actual target objects only. Projected stands translate source IDs explicitly;
     * a mapping does not assert identical original history. History imports preserve original IDs. */
    record RepositoryProof(BackupRepositoryKey targetKey, String physicalStorageName,
                           SnapshotContext.RepositoryState restoredState,
                           Map<String, String> commitMappings, String repositoryDigest) {
        public RepositoryProof {
            new RestoreRepositoryAllocation(targetKey, physicalStorageName);
            Objects.requireNonNull(restoredState); commitMappings = Map.copyOf(commitMappings);
            if (commitMappings.size() > BackupLimits.MAX_ITEMS || repositoryDigest == null
                    || !repositoryDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid Git restore proof");
            commitMappings.forEach((source, target) -> {
                if (!source.matches("[0-9a-f]{40}") || !target.matches("[0-9a-f]{40}"))
                    throw new IllegalArgumentException("Invalid restored commit mapping");
            });
            if (!restoredState.requiredCommits().containsAll(commitMappings.values()))
                throw new IllegalArgumentException("Restored commit mapping has no target object proof");
        }
    }
}
