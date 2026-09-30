package com.taxonomy.backup;

import java.util.*;

/** Explicit selection; selecting a repository never implicitly selects its workspaces. */
public sealed interface BackupScope permits BackupScope.Workspace, BackupScope.Repositories, BackupScope.Installation {
    Set<String> repositoryIds();
    Set<BackupRepositoryKey> selectedRepositories();

    record Workspace(String repositoryId, String workspaceId) implements BackupScope {
        public Workspace { BackupChecks.text(repositoryId, "repositoryId"); BackupChecks.text(workspaceId, "workspaceId"); }
        @Override public Set<String> repositoryIds() { return Set.of(repositoryId); }
        @Override public Set<BackupRepositoryKey> selectedRepositories() { return Set.of(new BackupRepositoryKey(repositoryId, workspaceId)); }
    }
    record Repositories(Map<String, Set<String>> workspacesByRepository) implements BackupScope {
        public Repositories {
            Objects.requireNonNull(workspacesByRepository, "workspacesByRepository");
            if (workspacesByRepository.isEmpty()) throw new IllegalArgumentException("Select at least one repository");
            BackupChecks.count(workspacesByRepository.size());
            var copy = new TreeMap<String, Set<String>>();
            var workspaceIds = new HashSet<String>();
            workspacesByRepository.forEach((repo, workspaces) -> {
                BackupChecks.text(repo, "repositoryId");
                BackupChecks.count(workspaces.size());
                var owned = new TreeSet<String>();
                for (String workspace : workspaces) {
                    BackupChecks.text(workspace, "workspaceId");
                    if (!workspaceIds.add(workspace)) throw new IllegalArgumentException("Workspace selected under multiple repositories");
                    owned.add(workspace);
                }
                copy.put(repo, Collections.unmodifiableSet(owned));
            });
            workspacesByRepository = Collections.unmodifiableMap(copy);
            BackupChecks.count(workspacesByRepository.size() + workspaceIds.size());
        }
        @Override public Set<String> repositoryIds() { return workspacesByRepository.keySet(); }
        @Override public Set<BackupRepositoryKey> selectedRepositories() {
            var selected = new HashSet<BackupRepositoryKey>();
            workspacesByRepository.forEach((repo, workspaces) -> {
                selected.add(new BackupRepositoryKey(repo, null));
                workspaces.forEach(workspace -> selected.add(new BackupRepositoryKey(repo, workspace)));
            });
            return Set.copyOf(selected);
        }
    }
    record Installation() implements BackupScope {
        // Empty here means an installation selection, NOT an empty authorized closure.
        @Override public Set<String> repositoryIds() { return Set.of(); }
        @Override public Set<BackupRepositoryKey> selectedRepositories() { return Set.of(); }
    }
}
