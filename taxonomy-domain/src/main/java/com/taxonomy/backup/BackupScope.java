package com.taxonomy.backup;

import java.util.*;

/** Explicit selection; selecting a repository never implicitly selects its workspaces. */
public sealed interface BackupScope permits BackupScope.Workspace, BackupScope.Repositories, BackupScope.Installation {
    Set<String> repositoryIds();

    record Workspace(String repositoryId, String workspaceId) implements BackupScope {
        public Workspace { BackupChecks.text(repositoryId, "repositoryId"); BackupChecks.text(workspaceId, "workspaceId"); }
        @Override public Set<String> repositoryIds() { return Set.of(repositoryId); }
    }
    record Repositories(Map<String, Set<String>> workspacesByRepository) implements BackupScope {
        public Repositories {
            Objects.requireNonNull(workspacesByRepository, "workspacesByRepository");
            if (workspacesByRepository.isEmpty()) throw new IllegalArgumentException("Select at least one repository");
            var copy = new TreeMap<String, Set<String>>();
            var workspaceIds = new HashSet<String>();
            workspacesByRepository.forEach((repo, workspaces) -> {
                BackupChecks.text(repo, "repositoryId");
                var owned = new TreeSet<String>();
                for (String workspace : workspaces) {
                    BackupChecks.text(workspace, "workspaceId");
                    if (!workspaceIds.add(workspace)) throw new IllegalArgumentException("Workspace selected under multiple repositories");
                    owned.add(workspace);
                }
                copy.put(repo, Collections.unmodifiableSet(owned));
            });
            workspacesByRepository = Collections.unmodifiableMap(copy);
        }
        @Override public Set<String> repositoryIds() { return workspacesByRepository.keySet(); }
    }
    record Installation() implements BackupScope {
        // Empty here means an installation selection, NOT an empty authorized closure.
        @Override public Set<String> repositoryIds() { return Set.of(); }
    }
}
