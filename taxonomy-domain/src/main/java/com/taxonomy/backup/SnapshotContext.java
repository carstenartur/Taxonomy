package com.taxonomy.backup;

import java.time.Instant;
import java.util.*;

/** Immutable capture evidence shared by module-owned contributors. */
public record SnapshotContext(BackupId backupId, AuthorizedBackupRequest authorization, Instant startedAt,
                              Instant completedAt, long fencingGeneration,
                              Map<BackupRepositoryKey, RepositoryState> repositories, Map<BackupComponentId, Integer> componentVersions) {
    public record WorkingState(long semanticRevision, String checkpointCommit, long checkpointRevision) {
        public WorkingState {
            if (semanticRevision < 0 || checkpointRevision < 0 || checkpointRevision > semanticRevision) {
                throw new IllegalArgumentException("Invalid working-state revisions");
            }
            if (checkpointCommit != null) BackupChecks.hash(checkpointCommit, 40, "checkpointCommit");
        }
    }
    public record RepositoryState(Map<String, String> refs, String symbolicHead, Map<String, WorkingState> workingStates, Set<String> requiredCommits) {
        public RepositoryState {
            refs = Map.copyOf(refs); requiredCommits = Set.copyOf(requiredCommits);
            workingStates = Map.copyOf(workingStates);
            BackupChecks.count(refs.size()); BackupChecks.count(requiredCommits.size()); BackupChecks.count(workingStates.size());
            refs.forEach((ref, hash) -> { BackupChecks.text(ref, "ref"); BackupChecks.hash(hash, 40, "objectId"); });
            requiredCommits.forEach(hash -> BackupChecks.hash(hash, 40, "commit"));
            workingStates.keySet().forEach(branch -> BackupChecks.text(branch, "branch"));
            if (symbolicHead != null) {
                BackupChecks.text(symbolicHead, "symbolicHead");
                if (!refs.isEmpty() && !refs.containsKey(symbolicHead)) throw new IllegalArgumentException("HEAD target is missing");
            }
        }
    }
    public SnapshotContext {
        Objects.requireNonNull(backupId); Objects.requireNonNull(authorization); Objects.requireNonNull(startedAt); Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(startedAt) || fencingGeneration < 1) throw new IllegalArgumentException("Invalid capture interval or generation");
        repositories = Map.copyOf(repositories); componentVersions = Map.copyOf(componentVersions);
        BackupChecks.count(repositories.size()); BackupChecks.count(componentVersions.size());
        componentVersions.values().forEach(v -> { if (v < 1) throw new IllegalArgumentException("Invalid component version"); });
        if (!(authorization.request().scope() instanceof BackupScope.Installation)
                && !repositories.keySet().equals(authorization.request().scope().selectedRepositories())) {
            throw new IllegalArgumentException("Captured repository closure differs from authorized scope");
        }
    }
}
