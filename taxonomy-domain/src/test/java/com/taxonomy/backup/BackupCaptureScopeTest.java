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
    @Test void manifestArchiveIdsMustMatchTheCapturedRepositorySetAndRemainOpaquePortableAndUnique() {
        var a = new BackupRepositoryKey("repo", null); var b = new BackupRepositoryKey("repo", "private");
        var request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Repositories(Map.of("repo", Set.of("private"))), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var auth = new AuthorizedBackupRequest(request, PrincipalId.create(), "decision", Instant.EPOCH, Set.of(BackupCapability.EXPORT_CURRENT));
        var states = Map.of(a, state('a', 1), b, state('b', 2));
        var ids = new HashMap<>(Map.of(a, "opaque-a", b, "opaque-b"));
        var snapshot = new SnapshotContext(BackupId.create(), auth, Instant.EPOCH, Instant.EPOCH, 1, states, Map.of(), ids);
        ids.clear(); assertEquals(Map.of(a, "opaque-a", b, "opaque-b"), snapshot.repositoryArchiveIds());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.repositoryArchiveIds().clear());
        for (var invalid : List.of(Map.of(a, "opaque-a"), Map.of(a, "same", b, "same"), Map.of(a, "same", b, "SAME"), Map.of(a, "unsafe/path", b, "safe"),
                Map.of(a, "a".repeat(129), b, "safe"), Map.of(a, "safe", new BackupRepositoryKey("foreign", null), "other"))) {
            assertThrows(IllegalArgumentException.class, () -> new SnapshotContext(snapshot.backupId(), auth, Instant.EPOCH, Instant.EPOCH, 1, states, Map.of(), invalid));
        }
    }
    @Test void repositoryArchiveIdsRejectReservedDeviceSegmentsBeforeAnyPayloadIsWritten() {
        var key = new BackupRepositoryKey("repo", "private");
        var request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo", "private"),
                new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var auth = new AuthorizedBackupRequest(request, PrincipalId.create(), "decision", Instant.EPOCH, Set.of(BackupCapability.EXPORT_CURRENT));
        for (var id : List.of("CON", "con", "PrN", "aux", "NUL", "COM1", "com9", "LPT1", "lpt9")) {
            assertThrows(IllegalArgumentException.class, () -> new SnapshotContext(BackupId.create(), auth,
                    Instant.EPOCH, Instant.EPOCH, 1, Map.of(key, state('a', 1)), Map.of(), Map.of(key, id)), id);
        }
        for (var id : List.of("COM0", "com10", "LPT0", "lpt10", "con-backup")) {
            assertDoesNotThrow(() -> new SnapshotContext(BackupId.create(), auth,
                    Instant.EPOCH, Instant.EPOCH, 1, Map.of(key, state('a', 1)), Map.of(), Map.of(key, id)), id);
        }
    }
    private SnapshotContext.RepositoryState state(char head, long revision) {
        String commit = String.valueOf(head).repeat(40);
        return new SnapshotContext.RepositoryState(Map.of("refs/heads/main", commit), "refs/heads/main",
                Map.of("main", new SnapshotContext.WorkingState(revision, commit, 1), "feature", new SnapshotContext.WorkingState(revision + 1, commit, 1)), Set.of(commit));
    }
}
