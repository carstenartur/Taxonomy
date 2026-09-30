package com.taxonomy.backup;

import java.util.Objects;

/** Validates the three independent selection axes before authorization or I/O. */
public record BackupRequest(BackupProfile profile, BackupScope scope, BackupTime time,
                            GitRepresentation gitRepresentation, SecretsSelection secrets) {
    public BackupRequest {
        Objects.requireNonNull(profile); Objects.requireNonNull(scope); Objects.requireNonNull(time);
        Objects.requireNonNull(gitRepresentation); Objects.requireNonNull(secrets);
        if (profile.isInstallation() != (scope instanceof BackupScope.Installation)) {
            throw new IllegalArgumentException("Profile and installation scope disagree");
        }
        boolean validTime = switch (profile) {
            case CURRENT_STATE, INSTALLATION_CURRENT -> time instanceof BackupTime.Current;
            case SELECTED_VERSION -> time instanceof BackupTime.SelectedVersion;
            case REPOSITORY_HISTORY, INSTALLATION_FULL -> time instanceof BackupTime.History;
        };
        if (!validTime) throw new IllegalArgumentException("Profile and time selection disagree");
        if (time instanceof BackupTime.SelectedVersion version && !version.commitsByRepository().keySet().equals(scope.selectedRepositories())) {
            throw new IllegalArgumentException("Select exactly one commit for each selected repository");
        }
        if (profile.includesHistory() && gitRepresentation == GitRepresentation.NONE) {
            throw new IllegalArgumentException("History requires a Git representation");
        }
        if (secrets == SecretsSelection.INCLUDE_ENCRYPTED && profile != BackupProfile.INSTALLATION_FULL) {
            throw new IllegalArgumentException("Secrets require an encrypted full installation backup");
        }
    }
}
