package com.taxonomy.backup;

import java.util.*;

public sealed interface BackupTime permits BackupTime.Current, BackupTime.SelectedVersion, BackupTime.History {
    record Current() implements BackupTime { }
    record History() implements BackupTime { }
    record SelectedVersion(Map<BackupRepositoryKey, String> commitsByRepository) implements BackupTime {
        public SelectedVersion {
            if (Objects.requireNonNull(commitsByRepository).isEmpty()) throw new IllegalArgumentException("Missing selected commits");
            BackupChecks.count(commitsByRepository.size());
            commitsByRepository.forEach((repo, commit) -> {
                Objects.requireNonNull(repo); BackupChecks.hash(commit, 40, "commit");
            });
            commitsByRepository = Map.copyOf(commitsByRepository);
        }
    }
}
