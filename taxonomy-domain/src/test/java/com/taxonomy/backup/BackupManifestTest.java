package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupManifestTest {
    private final BackupComponentId id = new BackupComponentId("workspace");
    private final BackupEntry payload = new BackupEntry("data/workspace/current.ndjson", 3, "a".repeat(64));
    private final BackupRequest request = new BackupRequest(BackupProfile.CURRENT_STATE,
            new BackupScope.Workspace("repo-a", "workspace-a"), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);

    private BackupManifest manifest(int format, Set<String> features, List<BackupManifest.Component> components, List<BackupEntry> entries) {
        var captured = new SnapshotContext.RepositoryState(Map.of("refs/heads/main", "b".repeat(40)), "refs/heads/main", Map.of("main", new SnapshotContext.WorkingState(7, "b".repeat(40), 5)), Set.of());
        return new BackupManifest(format, "1.4.0", "build-a", BackupId.create(), "source-installation", request,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "writer-barrier:1", features, components,
                List.of(new BackupManifest.Repository(new BackupRepositoryKey("repo-a", "workspace-a"), "opaque-a", GitRepresentation.NONE, captured, null, null, "b".repeat(40))),
                entries, List.of(), List.of("operation-history"));
    }
    private BackupManifest.Component component(Set<BackupComponentId> dependencies) {
        return new BackupManifest.Component(id, 1, BackupCompleteness.COMPLETE, List.of(payload.path()), dependencies);
    }
    @Test void rejectsUnsupportedFormatsAndUnknownMandatoryFeatures() {
        assertThrows(IllegalArgumentException.class, () -> manifest(2, Set.of(), List.of(component(Set.of())), List.of(payload)));
        assertThrows(IllegalArgumentException.class, () -> manifest(1, Set.of("future"), List.of(component(Set.of())), List.of(payload)));
    }
    @Test void rejectsUnownedMissingAndDuplicatePayloads() {
        assertThrows(IllegalArgumentException.class, () -> manifest(1, Set.of(), List.of(), List.of(payload)));
        assertThrows(IllegalArgumentException.class, () -> manifest(1, Set.of(), List.of(component(Set.of())), List.of()));
        assertThrows(IllegalArgumentException.class, () -> manifest(1, Set.of(), List.of(component(Set.of())), List.of(payload, payload)));
    }
    @Test void rejectsMissingAndCyclicComponentDependencies() {
        assertThrows(IllegalArgumentException.class, () -> manifest(1, Set.of(), List.of(component(Set.of(new BackupComponentId("missing")))), List.of(payload)));
        assertThrows(IllegalArgumentException.class, () -> manifest(1, Set.of(), List.of(component(Set.of(id))), List.of(payload)));
    }
    @Test void requiresSameApplicationAndExplicitlySupportedComponentVersions() {
        var manifest = manifest(1, BackupManifest.SUPPORTED_FEATURES, List.of(component(Set.of())), List.of(payload));
        assertDoesNotThrow(() -> manifest.requireCompatible("1.4.0", Map.of(id, 1)));
        assertThrows(IllegalArgumentException.class, () -> manifest.requireCompatible("1.3.0", Map.of(id, 1)));
        assertThrows(IllegalArgumentException.class, () -> manifest.requireCompatible("1.4.0", Map.of(id, 2)));
        assertThrows(IllegalArgumentException.class, () -> manifest.requireCompatible("1.4.0", Map.of()));
    }
    @Test void refusesUnsafeEntryNamesAndMalformedDigests() {
        for (var path : List.of("../escape", "/absolute", "data//double", "data/./dot", "data/../escape", "C:/drive", "data\\backslash", "data/")) {
            assertThrows(IllegalArgumentException.class, () -> new BackupEntry(path, 0, "a".repeat(64)), path);
        }
        assertThrows(IllegalArgumentException.class, () -> new BackupEntry("data/ok", -1, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new BackupEntry("data/ok", 1, "short"));
    }
    @Test void metadataMustObeyWireStringAndCollectionLimits() {
        var m = manifest(1, BackupManifest.SUPPORTED_FEATURES, List.of(component(Set.of())), List.of(payload));
        for (var bad : List.of(List.of(""), List.of("x".repeat(513)), Collections.nCopies(10001, "omission"))) {
            assertThrows(IllegalArgumentException.class, () -> new BackupManifest(m.formatVersion(), m.applicationVersion(), m.build(), m.backupId(),
                    m.sourceInstallationId(), m.request(), m.captureStartedAt(), m.captureCompletedAt(), m.consistencyEvidence(), m.requiredFeatures(),
                    m.components(), m.repositories(), m.entries(), bad, List.of()));
            assertThrows(IllegalArgumentException.class, () -> new BackupManifest(m.formatVersion(), m.applicationVersion(), m.build(), m.backupId(),
                    m.sourceInstallationId(), m.request(), m.captureStartedAt(), m.captureCompletedAt(), m.consistencyEvidence(), m.requiredFeatures(),
                    m.components(), m.repositories(), m.entries(), List.of(), bad));
        }
    }

    @Test void repositoryRepresentationMustMatchTheCurrentStateRequest() {
        for (var requested : GitRepresentation.values()) {
            var selection = new BackupRequest(BackupProfile.CURRENT_STATE, request.scope(),
                    new BackupTime.Current(), requested, SecretsSelection.EXCLUDE);
            assertRepositoryRepresentations(selection);
        }
    }

    @Test void historyCannotSubstituteAnotherSelectedGitRepresentation() {
        for (var requested : List.of(GitRepresentation.BUNDLE, GitRepresentation.BARE, GitRepresentation.WORKTREE)) {
            var selection = new BackupRequest(BackupProfile.REPOSITORY_HISTORY, request.scope(),
                    new BackupTime.History(), requested, SecretsSelection.EXCLUDE);
            assertRepositoryRepresentations(selection);
        }
    }

    @Test void opaqueRepositoryIdsCannotAliasOnCaseInsensitiveFilesystems() {
        var m = manifest(1, Set.of(), List.of(component(Set.of())), List.of(payload));
        var original = m.repositories().getFirst(); var other = new BackupRepositoryKey("repo-a", "workspace-b");
        var scope = new BackupScope.Repositories(Map.of("repo-a", Set.of("workspace-a", "workspace-b")));
        var selection = new BackupRequest(BackupProfile.CURRENT_STATE, scope, new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var central = new BackupManifest.Repository(new BackupRepositoryKey("repo-a", null), "central", GitRepresentation.NONE, original.captured(), null, null, null);
        var duplicate = new BackupManifest.Repository(other, original.archiveId().toUpperCase(Locale.ROOT), GitRepresentation.NONE, original.captured(), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new BackupManifest(m.formatVersion(), m.applicationVersion(), m.build(), m.backupId(), m.sourceInstallationId(), selection,
                m.captureStartedAt(), m.captureCompletedAt(), m.consistencyEvidence(), m.requiredFeatures(), m.components(), List.of(central, original, duplicate), m.entries(), m.dependencies(), m.omissions()));
    }

    @Test void opaqueRepositoryIdsRejectReservedDeviceSegmentsEvenForAnEmptyRepository() {
        var state = new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of());
        var key = new BackupRepositoryKey("repo", null);
        for (var id : List.of("CON", "con", "PrN", "aux", "NUL", "COM1", "com9", "LPT1", "lpt9")) {
            assertThrows(IllegalArgumentException.class, () -> new BackupManifest.Repository(key, id,
                    GitRepresentation.NONE, state, null, null, null), id);
        }
        for (var id : List.of("COM0", "com10", "LPT0", "lpt10", "con-backup")) {
            assertDoesNotThrow(() -> new BackupManifest.Repository(key, id,
                    GitRepresentation.NONE, state, null, null, null), id);
        }
    }

    private void assertRepositoryRepresentations(BackupRequest selection) {
        var m = manifest(1, Set.of(), List.of(component(Set.of())), List.of(payload));
        var repository = m.repositories().getFirst();
        for (var representation : GitRepresentation.values()) {
            org.junit.jupiter.api.function.Executable create = () -> new BackupManifest(m.formatVersion(),
                    m.applicationVersion(), m.build(), m.backupId(), m.sourceInstallationId(), selection,
                    m.captureStartedAt(), m.captureCompletedAt(), m.consistencyEvidence(), m.requiredFeatures(),
                    m.components(), List.of(new BackupManifest.Repository(repository.id(), repository.archiveId(),
                            representation, repository.captured(), null, null, repository.sourceCommit())),
                    m.entries(), m.dependencies(), m.omissions());
            String description = "requested " + selection.gitRepresentation() + ", captured " + representation;
            if (representation == selection.gitRepresentation()) assertDoesNotThrow(create, description);
            else assertThrows(IllegalArgumentException.class, create, description);
        }
    }

}
