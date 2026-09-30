package com.taxonomy.backup;

import java.time.Instant;
import java.util.*;

/** Immutable capture evidence shared by module-owned contributors. */
public record SnapshotContext(BackupId backupId, AuthorizedBackupRequest authorization, Instant startedAt,
                              Instant completedAt, long fencingGeneration,
                              Map<String, RepositoryState> repositories, Map<BackupComponentId, Integer> componentVersions) {
    public record RepositoryState(Map<String, String> refs, String symbolicHead, long semanticRevision, Set<String> requiredCommits) {
        public RepositoryState {
            refs = Map.copyOf(refs); requiredCommits = Set.copyOf(requiredCommits);
            refs.forEach((ref, hash) -> { BackupChecks.text(ref, "ref"); BackupChecks.hash(hash, 40, "objectId"); });
            requiredCommits.forEach(hash -> BackupChecks.hash(hash, 40, "commit"));
            if (symbolicHead != null && !refs.containsKey(symbolicHead)) throw new IllegalArgumentException("HEAD target is missing");
            if (semanticRevision < 0) throw new IllegalArgumentException("Negative semantic revision");
        }
    }
    public SnapshotContext {
        Objects.requireNonNull(backupId); Objects.requireNonNull(authorization); Objects.requireNonNull(startedAt); Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(startedAt) || fencingGeneration < 1) throw new IllegalArgumentException("Invalid capture interval or generation");
        repositories = Map.copyOf(repositories); componentVersions = Map.copyOf(componentVersions);
        componentVersions.values().forEach(v -> { if (v < 1) throw new IllegalArgumentException("Invalid component version"); });
        if (!(authorization.request().scope() instanceof BackupScope.Installation)
                && !repositories.keySet().equals(authorization.request().scope().repositoryIds())) {
            throw new IllegalArgumentException("Captured repository closure differs from authorized scope");
        }
    }
}
