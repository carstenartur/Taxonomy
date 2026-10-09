package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.backup.snapshot.BackupSnapshotCoordinator.*;
import com.taxonomy.workspace.backup.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
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

/** Real stand projection and synthetic bundle through encrypted capture, durable reopening and archive verification. */
class GitStandBundleArchiveIT {
    @TempDir Path root;
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repository", "private-workspace");
    private static final BackupComponentId COMPONENT = new BackupComponentId("workspace");
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);
    private static final Instant AT = Instant.parse("2026-01-02T03:04:05Z");

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "SELECTED_VERSION", "INSTALLATION_CURRENT"})
    void verifiedEncryptedArchiveContainsACloneableProjectedStandAfterTheSourceHasClosed(BackupProfile profile) throws Exception {
        StreamingAeadConfig.register(); var protection = new TinkArchiveProtection(KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB));
        CapturedBackup captured; AuthorizedBackupRequest authorization; String sourceCommit;
        String expected = profile == BackupProfile.SELECTED_VERSION ? "SELECTED-CONTENT" : "SAVED-CONTENT";
        try (var f = new GitStandExportIT.Fixture()) {
            String selected = f.commit(GitStandExportIT.document("SELECTED-CONTENT", "HIDDEN-SELECTED"));
            String current = f.commit(GitStandExportIT.document("CURRENT-CONTENT", "HIDDEN-CURRENT")); f.saved(current, GitStandExportIT.document("SAVED-CONTENT", "HIDDEN-SAVED"));
            sourceCommit = profile == BackupProfile.SELECTED_VERSION ? selected : current;
            var actor = PrincipalId.create(); var service = new BackupAuthorizationService(access(actor), Clock.fixed(AT, ZoneOffset.UTC));
            var request = new BackupRequest(profile, profile == BackupProfile.INSTALLATION_CURRENT ? new BackupScope.Installation() : new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()),
                    profile == BackupProfile.SELECTED_VERSION ? new BackupTime.SelectedVersion(Map.of(KEY, selected)) : new BackupTime.Current(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
            authorization = service.authorize(actor, request);
            var coordinator = coordinator(f, service, protection, false, root.resolve("spool"));
            captured = coordinator.capture(authorization);
            try (var files = Files.walk(captured.directory())) {
                for (var file : files.filter(Files::isRegularFile).toList()) assertThat(new String(Files.readAllBytes(file), UTF_8)).doesNotContain(expected, "# v2 git bundle");
            }
        }
        var repository = captured.manifest().repositories().getFirst(); String head = repository.exportedHead();
        String payload = "repositories/" + repository.archiveId() + "/stand.bundle";
        assertThat(repository.sourceCommit()).isEqualTo(sourceCommit); assertThat(repository.sourceRepositoryId()).isNull();
        assertThat(captured.snapshot().exportedHeads()).isEqualTo(Map.of(KEY, head));
        assertThat(captured.manifest().entries()).extracting(BackupEntry::path).containsExactlyInAnyOrder(payload, "verification/capture.json");
        Path archive = root.resolve("stand.taxbackup");
        try (captured; var reopened = CapturedBackup.open(captured.directory(), authorization, protection)) {
            assertThat(reopened.snapshot()).isEqualTo(captured.snapshot());
            assertThat(new BackupArchiveWriter(protection, ArchiveLimits.defaults(), com.taxonomy.backup.runtime.BackupFeaturePrerequisites.discover()).write(reopened, archive).encrypted()).isTrue();
        }
        Path bundle = root.resolve("stand.bundle");
        try (var verified = new BackupArchiveReader(protection, ArchiveLimits.defaults(), "1.4.0-SNAPSHOT", Map.of(COMPONENT, 1, new BackupComponentId("capture-proof"), 1), com.taxonomy.backup.runtime.BackupFeaturePrerequisites.discover()).verify(archive)) {
            assertThat(verified.manifest().repositories().getFirst().exportedHead()).isEqualTo(head);
            try (var input = verified.openEntry(payload)) { Files.copy(input, bundle); }
        }
        try (var git = Git.cloneRepository().setURI(bundle.toUri().toString()).setDirectory(root.resolve("offline.git").toFile()).setBare(true).call();
             var walk = new RevWalk(git.getRepository())) {
            var commit = walk.parseCommit(git.getRepository().resolve("HEAD")); assertThat(commit.name()).isEqualTo(head); assertThat(commit.getParentCount()).isZero();
            try (var tree = TreeWalk.forPath(git.getRepository(), "architecture.taxdsl", commit.getTree())) {
                assertThat(tree).isNotNull(); String document = new String(git.getRepository().open(tree.getObjectId(0)).getBytes(), UTF_8);
                assertThat(document).contains(expected, "CURRENT-ARCHITECTURE").doesNotContain("HIDDEN-", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE", "CURRENT-CONTENT");
            }
        }
    }

    @Test void aChangedSavedBodyWithTheSameRefsAndRevisionsCannotPublishACapture() throws Exception {
        Path spool = root.resolve("failed-spool"); StreamingAeadConfig.register();
        var protection = new TinkArchiveProtection(KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB));
        try (var f = new GitStandExportIT.Fixture()) {
            String head = f.commit(GitStandExportIT.document("COMMITTED", "HIDDEN")); f.saved(head, GitStandExportIT.document("SAVED-FIRST", "HIDDEN"));
            var actor = PrincipalId.create(); var service = new BackupAuthorizationService(access(actor), Clock.systemUTC());
            var request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()), new BackupTime.Current(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
            assertThatThrownBy(() -> coordinator(f, service, protection, true, spool).capture(service.authorize(actor, request)))
                    .isInstanceOf(IOException.class).hasMessageContaining("exported head changed");
            try (var entries = Files.list(spool)) { assertThat(entries.toList()).isEmpty(); }
        }
    }

    private static BackupSnapshotCoordinator coordinator(GitStandExportIT.Fixture f, BackupAuthorizationService authorization,
                                                          ArchiveProtectionProvider protection, boolean change, Path spool) {
        var git = new GitStandBackupContributor(f.source, LIMITS);
        var inventory = new Inventory() {
            @Override public CapturePlan inspect(AuthorizedBackupRequest auth) throws IOException { throw new IOException("Checkpoint required"); }
            @Override public CapturePlan inspect(AuthorizedBackupRequest auth, BackupCheckpoint checkpoint) throws IOException {
                var repositories = git.inspect(auth, Set.of(KEY), checkpoint);
                if (change) f.jdbc.update("update editor_workspace set dsl=?", "1:" + GitStandExportIT.document("SAVED-SECOND", "HIDDEN"));
                return new CapturePlan("1.4.0-SNAPSHOT", "bundle-fixture", "source-fixture", repositories,
                        Map.of(COMPONENT, new CaptureComponent(1, BackupCompleteness.DEPENDENT, Set.of())), List.of(),
                        List.of("Integration fixture; production capture still requires complete application reference closure"));
            }
        };
        BackupMaintenanceLease.initialize(f.database);
        return new BackupSnapshotCoordinator(new BackupMaintenanceLease(f.database, Duration.ofSeconds(30)), authorization, inventory,
                List.of(git), spool, CaptureLimits.defaults(), Clock.systemUTC(), protection, com.taxonomy.backup.runtime.BackupFeaturePrerequisites.discover());
    }
    private static BackupAccessPolicy access(PrincipalId actor) {
        var access = mock(BackupAccessPolicy.class); when(access.isEnabled(actor)).thenReturn(true); when(access.hasCapability(eq(actor), any(), any())).thenReturn(true);
        when(access.canRead(actor, KEY)).thenReturn(true); when(access.canReadVersion(eq(actor), eq(KEY), anyString())).thenReturn(true); return access;
    }

}
