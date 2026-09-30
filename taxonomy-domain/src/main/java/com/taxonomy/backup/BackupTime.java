package com.taxonomy.backup;

import java.util.*;

public sealed interface BackupTime permits BackupTime.Current, BackupTime.SelectedVersion, BackupTime.History {
    record Current() implements BackupTime { }
    record History() implements BackupTime { }
    record SelectedVersion(Map<String, String> commitsByRepository) implements BackupTime {
        public SelectedVersion {
            if (Objects.requireNonNull(commitsByRepository).isEmpty()) throw new IllegalArgumentException("Missing selected commits");
            commitsByRepository.forEach((repo, commit) -> {
                BackupChecks.text(repo, "repositoryId"); BackupChecks.hash(commit, 40, "commit");
            });
            commitsByRepository = Map.copyOf(commitsByRepository);
        }
    }
}
