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
        var captured = new SnapshotContext.RepositoryState(Map.of("refs/heads/main", "b".repeat(40)), "refs/heads/main", 7, Set.of());
        return new BackupManifest(format, "1.4.0", "build-a", BackupId.create(), "source-installation", request,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "writer-barrier:1", features, components,
                List.of(new BackupManifest.Repository("repo-a", "opaque-a", GitRepresentation.NONE, captured, null, null, "b".repeat(40))),
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
}
