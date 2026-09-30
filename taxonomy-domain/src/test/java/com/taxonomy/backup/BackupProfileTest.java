package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupProfileTest {
    private final BackupScope scope = new BackupScope.Workspace("repo-a", "workspace-a");

    @Test void snapshotProfilesRejectHistoryAndInstallationScope() {
        assertThrows(IllegalArgumentException.class, () -> new BackupRequest(
                BackupProfile.CURRENT_STATE, scope, new BackupTime.History(), GitRepresentation.BARE, SecretsSelection.EXCLUDE));
        assertThrows(IllegalArgumentException.class, () -> new BackupRequest(
                BackupProfile.CURRENT_STATE, new BackupScope.Installation(), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
        assertThrows(IllegalArgumentException.class, () -> new BackupRequest(
                BackupProfile.INSTALLATION_CURRENT, scope, new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
    }
    @Test void selectedVersionRequiresExactVersionsOfExactlySelectedRepositories() {
        var version = new BackupTime.SelectedVersion(Map.of("repo-a", "a".repeat(40)));
        assertDoesNotThrow(() -> new BackupRequest(BackupProfile.SELECTED_VERSION, scope, version, GitRepresentation.WORKTREE, SecretsSelection.EXCLUDE));
        assertThrows(IllegalArgumentException.class, () -> new BackupRequest(BackupProfile.SELECTED_VERSION, scope,
                new BackupTime.SelectedVersion(Map.of("other", "a".repeat(40))), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
        assertThrows(IllegalArgumentException.class, () -> new BackupTime.SelectedVersion(Map.of("repo-a", "main")));
    }
    @Test void historyRequiresGitAndSecretsRequireFullInstallation() {
        assertThrows(IllegalArgumentException.class, () -> new BackupRequest(BackupProfile.REPOSITORY_HISTORY, scope,
                new BackupTime.History(), GitRepresentation.NONE, SecretsSelection.EXCLUDE));
        assertThrows(IllegalArgumentException.class, () -> new BackupRequest(BackupProfile.CURRENT_STATE, scope,
                new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.INCLUDE_ENCRYPTED));
        assertDoesNotThrow(() -> new BackupRequest(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation(),
                new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.INCLUDE_ENCRYPTED));
    }
    @Test void scopesCannotImplicitlyIncludeOtherWorkspacesAndAreImmutable() {
        var workspaces = new HashSet<>(Set.of("my-workspace"));
        var selected = new BackupScope.Repositories(Map.of("repo-a", workspaces));
        workspaces.add("private-workspace");
        assertEquals(Set.of("my-workspace"), selected.workspacesByRepository().get("repo-a"));
        assertThrows(UnsupportedOperationException.class, () -> selected.workspacesByRepository().put("other", Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> selected.workspacesByRepository().get("repo-a").add("other"));
        assertThrows(IllegalArgumentException.class, () -> new BackupScope.Repositories(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new BackupScope.Repositories(Map.of("a", Set.of("w"), "b", Set.of("w"))));
    }
    @Test void currentProfilesExcludeHistoryButKeepExplicitProvenance() {
        assertFalse(BackupProfile.CURRENT_STATE.includesHistory());
        assertFalse(BackupProfile.SELECTED_VERSION.includesHistory());
        assertFalse(BackupProfile.INSTALLATION_CURRENT.includesHistory());
        assertTrue(BackupProfile.REPOSITORY_HISTORY.includesHistory());
        assertTrue(BackupProfile.INSTALLATION_FULL.includesHistory());
    }
}
