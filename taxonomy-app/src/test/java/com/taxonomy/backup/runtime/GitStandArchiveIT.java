package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.backup.snapshot.BackupSnapshotCoordinator.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.workspace.backup.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The Git stand component through the real fence, encrypted staging, archive writer and verified reader. */
class GitStandArchiveIT {
    @TempDir Path root;
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repository", "private-workspace");
    private static final BackupComponentId WORKSPACE = new BackupComponentId("workspace");
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "SELECTED_VERSION"})
    void encryptedArchiveKeepsManifestIdentityAndProjectedFilesAfterSourceHandlesClose(BackupProfile profile) throws Exception {
        var protection = encryption(); CapturedBackup captured; AuthorizedBackupRequest authorization;
        String expected = profile == BackupProfile.CURRENT_STATE ? "SAVED-DRAFT-CONTENT" : "SELECTED-VERSION-CONTENT";
        try (var f = new GitStandExportIT.Fixture()) {
            String selected = f.commit(GitStandExportIT.document("SELECTED-VERSION-CONTENT", "HIDDEN-SELECTED-HISTORY"));
            String current = f.commit(GitStandExportIT.document("COMMITTED-OLD-CONTENT", "HIDDEN-CURRENT-HISTORY"));
            f.saved(current, GitStandExportIT.document("SAVED-DRAFT-CONTENT", "HIDDEN-SAVED-HISTORY"));
            var request = new BackupRequest(profile, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()),
                    profile == BackupProfile.CURRENT_STATE ? new BackupTime.Current() : new BackupTime.SelectedVersion(Map.of(KEY, selected)), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
            var actor = PrincipalId.create(); var access = access(actor); var service = new BackupAuthorizationService(access, Clock.systemUTC()); authorization = service.authorize(actor, request);
            var git = new GitStandBackupContributor(f.source, LIMITS); var coordinator = coordinator(f, service, git, protection, false, root.resolve("spool"));
            captured = coordinator.capture(authorization);
            assertThat(captured.snapshot().repositoryArchiveIds().get(KEY)).isEqualTo(captured.manifest().repositories().getFirst().archiveId());
            try (var files = Files.walk(captured.directory())) {
                for (var file : files.filter(Files::isRegularFile).toList()) assertThat(new String(Files.readAllBytes(file), UTF_8)).doesNotContain(expected);
            }
        }
        // Source sessions are closed; all archive inputs now come exclusively from the durable encrypted capture.
        Path archive = root.resolve("stand.taxbackup"); BackupManifest expectedManifest;
        try (captured; var reopened = CapturedBackup.open(captured.directory(), authorization, protection)) {
            expectedManifest = captured.manifest(); assertThat(reopened.snapshot()).isEqualTo(captured.snapshot());
            var published = new BackupArchiveWriter(protection, ArchiveLimits.defaults(), com.taxonomy.backup.runtime.BackupFeaturePrerequisites.discover()).write(reopened, archive); assertThat(published.encrypted()).isTrue();
        }
        var versions = Map.of(WORKSPACE, 1, new BackupComponentId("capture-proof"), 1);
        try (var verified = new BackupArchiveReader(protection, ArchiveLimits.defaults(), "1.4.0-SNAPSHOT", versions, com.taxonomy.backup.runtime.BackupFeaturePrerequisites.discover()).verify(archive)) {
            assertThat(verified.manifest()).isEqualTo(expectedManifest); String archiveId = verified.manifest().repositories().getFirst().archiveId();
            String metadata; try (var in = verified.openEntry("data/workspace/git-stands.ndjson")) { metadata = new String(in.readAllBytes(), UTF_8); }
            int files = 0;
            for (String line : metadata.split("\n")) {
                if (line.isBlank()) continue; var record = PortableRows.json().readTree(line); if (!record.path("recordType").asText().equals("file")) continue;
                files++; assertThat(record.path("archiveId").asText()).isEqualTo(archiveId); assertThat(record.path("path").asText()).isEqualTo("architecture.taxdsl");
                String path = record.path("entry").path("path").asText(); assertThat(path).startsWith("files/workspace-git/" + archiveId + "/");
                try (var input = verified.openEntry(path)) {
                    assertThat(new String(input.readAllBytes(), UTF_8)).contains(expected, "CURRENT-ARCHITECTURE")
                            .doesNotContain("HIDDEN-", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE", "COMMITTED-OLD-CONTENT");
                }
            }
            assertThat(files).isEqualTo(1);
        }
    }

    @Test void changedWorkingEvidenceAfterInventoryCannotPublishAnyCapture() throws Exception {
        Path spool = root.resolve("failed-spool");
        try (var f = new GitStandExportIT.Fixture()) {
            String head = f.commit(GitStandExportIT.document("OLD", "HIDDEN")); f.saved(head, GitStandExportIT.document("SAVED", "HIDDEN"));
            var actor = PrincipalId.create(); var service = new BackupAuthorizationService(access(actor), Clock.systemUTC());
            var request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
            var coordinator = coordinator(f, service, new GitStandBackupContributor(f.source, LIMITS), encryption(), true, spool);
            assertThatThrownBy(() -> coordinator.capture(service.authorize(actor, request))).isInstanceOf(IOException.class);
            try (var entries = Files.list(spool)) { assertThat(entries.toList()).isEmpty(); }
        }
    }

    private static BackupSnapshotCoordinator coordinator(GitStandExportIT.Fixture f, BackupAuthorizationService authorization,
                                                          GitStandBackupContributor git, ArchiveProtectionProvider protection, boolean change, Path spool) {
        BackupMaintenanceLease.initialize(f.database); var barrier = new BackupMaintenanceLease(f.database, Duration.ofSeconds(30));
        var inventory = new Inventory() {
            @Override public CapturePlan inspect(AuthorizedBackupRequest request) throws IOException { throw new IOException("Inventory checkpoint is required"); }
            @Override public CapturePlan inspect(AuthorizedBackupRequest request, BackupCheckpoint checkpoint) throws IOException {
                var repositories = git.inspect(request, request.request().scope().selectedRepositories(), checkpoint);
                if (change) f.jdbc.update("update editor_workspace set semantic_revision=semantic_revision+1");
                return new CapturePlan("1.4.0-SNAPSHOT", "git-stand-fixture", "source-fixture", repositories,
                        Map.of(WORKSPACE, new CaptureComponent(1, BackupCompleteness.DEPENDENT, Set.of())), List.of(), List.of("This fixture covers the Git stand component, not complete application capture"));
            }
        };
        return new BackupSnapshotCoordinator(barrier, authorization, inventory, List.of(git), spool, CaptureLimits.defaults(), Clock.systemUTC(), protection, com.taxonomy.backup.runtime.BackupFeaturePrerequisites.discover());
    }
    private static BackupAccessPolicy access(PrincipalId actor) {
        var access = mock(BackupAccessPolicy.class); when(access.isEnabled(actor)).thenReturn(true); when(access.hasCapability(eq(actor), any(), any())).thenReturn(true);
        when(access.canRead(actor, KEY)).thenReturn(true); when(access.canReadVersion(eq(actor), eq(KEY), anyString())).thenReturn(true); return access;
    }
    private static ArchiveProtectionProvider encryption() throws Exception {
        StreamingAeadConfig.register(); return new TinkArchiveProtection(KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB));
    }
}
