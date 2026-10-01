package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupCaptureScopeTest {
    @Test void centralAndTwoDivergentWorkspacesRetainTheirOwnRefsAndBranchRevisions() {
        var scope = new BackupScope.Repositories(Map.of("repo", Set.of("w1", "w2")));
        var central = new BackupRepositoryKey("repo", null);
        var w1 = new BackupRepositoryKey("repo", "w1");
        var w2 = new BackupRepositoryKey("repo", "w2");
        assertEquals(Set.of(central, w1, w2), scope.selectedRepositories());
        var request = new BackupRequest(BackupProfile.REPOSITORY_HISTORY, scope, new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
        var states = Map.of(central, state('a', 1), w1, state('b', 7), w2, state('c', 12));
        var context = new SnapshotContext(BackupId.create(), new AuthorizedBackupRequest(request, PrincipalId.create(), "decision", Instant.EPOCH,
                Set.of(BackupCapability.EXPORT_CURRENT, BackupCapability.EXPORT_HISTORY)), Instant.EPOCH, Instant.EPOCH, 1, states, Map.of());
        assertEquals("b".repeat(40), context.repositories().get(w1).refs().get("refs/heads/main"));
        assertEquals(12, context.repositories().get(w2).workingStates().get("main").semanticRevision());
        assertEquals(13, context.repositories().get(w2).workingStates().get("feature").semanticRevision());
        var missing = new HashMap<>(states); missing.remove(w2);
        assertThrows(IllegalArgumentException.class, () -> new SnapshotContext(context.backupId(), context.authorization(), Instant.EPOCH, Instant.EPOCH, 1, missing, Map.of()));
        var foreign = new HashMap<>(states); foreign.put(new BackupRepositoryKey("repo", "private-other-user"), state('d', 3));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotContext(context.backupId(), context.authorization(), Instant.EPOCH, Instant.EPOCH, 1, foreign, Map.of()));
    }
    @Test void workspaceOnlySelectionDoesNotImplicitlyCaptureCentralRepository() {
        assertEquals(Set.of(new BackupRepositoryKey("repo", "w1")), new BackupScope.Workspace("repo", "w1").selectedRepositories());
    }
    private SnapshotContext.RepositoryState state(char head, long revision) {
        String commit = String.valueOf(head).repeat(40);
        return new SnapshotContext.RepositoryState(Map.of("refs/heads/main", commit), "refs/heads/main",
                Map.of("main", new SnapshotContext.WorkingState(revision, commit, 1), "feature", new SnapshotContext.WorkingState(revision + 1, commit, 1)), Set.of(commit));
    }
}
