package com.taxonomy.backup;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

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

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "SELECTED_VERSION"})
    void verifiedEncryptedArchiveContainsACloneableProjectedStandAfterTheSourceHasClosed(BackupProfile profile) throws Exception {
        StreamingAeadConfig.register(); var protection = new TinkArchiveProtection(KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB));
        CapturedBackup captured; AuthorizedBackupRequest authorization; var head = new AtomicReference<String>();
        String expected = profile == BackupProfile.CURRENT_STATE ? "SAVED-CONTENT" : "SELECTED-CONTENT";
        try (var f = new GitStandExportIT.Fixture()) {
            String selected = f.commit(GitStandExportIT.document("SELECTED-CONTENT", "HIDDEN-SELECTED"));
            String current = f.commit(GitStandExportIT.document("CURRENT-CONTENT", "HIDDEN-CURRENT")); f.saved(current, GitStandExportIT.document("SAVED-CONTENT", "HIDDEN-SAVED"));
            var actor = PrincipalId.create(); var access = mock(BackupAccessPolicy.class);
            when(access.isEnabled(actor)).thenReturn(true); when(access.hasCapability(eq(actor), any(), any())).thenReturn(true);
            when(access.canRead(actor, KEY)).thenReturn(true); when(access.canReadVersion(eq(actor), eq(KEY), anyString())).thenReturn(true);
            var service = new BackupAuthorizationService(access, Clock.systemUTC());
            var request = new BackupRequest(profile, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()),
                    profile == BackupProfile.CURRENT_STATE ? new BackupTime.Current() : new BackupTime.SelectedVersion(Map.of(KEY, selected)), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
            authorization = service.authorize(actor, request);
            var inventory = new Inventory() {
                @Override public CapturePlan inspect(AuthorizedBackupRequest auth) throws IOException { throw new IOException("Checkpoint required"); }
                @Override public CapturePlan inspect(AuthorizedBackupRequest auth, BackupCheckpoint checkpoint) throws IOException {
                    try (var stand = f.source.open(auth, KEY, LIMITS, checkpoint)) {
                        head.set(GitStandBundle.capture(stand, AT, checkpoint).head());
                        var repository = new BackupManifest.Repository(KEY, "stand-a", GitRepresentation.BUNDLE, stand.state(), KEY, head.get(), profile == BackupProfile.SELECTED_VERSION ? selected : null);
                        return new CapturePlan("1.4.0-SNAPSHOT", "bundle-fixture", "source-fixture", List.of(repository),
                                Map.of(COMPONENT, new CaptureComponent(1, BackupCompleteness.DEPENDENT, Set.of())), List.of(), List.of("Integration fixture; production bundle assembly is not registered"));
                    }
                }
            };
            var contributor = new BackupContributor() {
                @Override public BackupComponentId componentId() { return COMPONENT; }
                @Override public int schemaVersion() { return 1; }
                @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
                    try (var stand = f.source.open(snapshot.authorization(), KEY, LIMITS, sink::checkpoint)) {
                        assertThat(stand.state()).isEqualTo(snapshot.repositories().get(KEY));
                        var bundle = GitStandBundle.capture(stand, AT, sink::checkpoint); assertThat(bundle.head()).isEqualTo(head.get());
                        bundle.write("repositories/" + snapshot.repositoryArchiveIds().get(KEY) + ".bundle", sink);
                    }
                }
            };
            BackupMaintenanceLease.initialize(f.database);
            var coordinator = new BackupSnapshotCoordinator(new BackupMaintenanceLease(f.database, Duration.ofSeconds(30)), service, inventory,
                    List.of(contributor), root.resolve("spool"), CaptureLimits.defaults(), Clock.systemUTC(), protection);
            captured = coordinator.capture(authorization);
            try (var files = Files.walk(captured.directory())) {
                for (var file : files.filter(Files::isRegularFile).toList()) assertThat(new String(Files.readAllBytes(file), UTF_8)).doesNotContain(expected, "# v2 git bundle");
            }
        }
        Path archive = root.resolve("stand.taxbackup");
        try (captured; var reopened = CapturedBackup.open(captured.directory(), authorization, protection)) {
            assertThat(new BackupArchiveWriter(protection, ArchiveLimits.defaults()).write(reopened, archive).encrypted()).isTrue();
        }
        Path bundle = root.resolve("stand.bundle");
        try (var verified = new BackupArchiveReader(protection, ArchiveLimits.defaults(), "1.4.0-SNAPSHOT", Map.of(COMPONENT, 1, new BackupComponentId("capture-proof"), 1)).verify(archive)) {
            assertThat(verified.manifest().repositories().getFirst().exportedHead()).isEqualTo(head.get());
            try (var input = verified.openEntry("repositories/stand-a.bundle")) { Files.copy(input, bundle); }
        }
        try (var git = Git.cloneRepository().setURI(bundle.toUri().toString()).setDirectory(root.resolve("offline.git").toFile()).setBare(true).call();
             var walk = new RevWalk(git.getRepository())) {
            var commit = walk.parseCommit(git.getRepository().resolve("HEAD")); assertThat(commit.name()).isEqualTo(head.get()); assertThat(commit.getParentCount()).isZero();
            try (var tree = TreeWalk.forPath(git.getRepository(), "architecture.taxdsl", commit.getTree())) {
                assertThat(tree).isNotNull(); String document = new String(git.getRepository().open(tree.getObjectId(0)).getBytes(), UTF_8);
                assertThat(document).contains(expected, "CURRENT-ARCHITECTURE").doesNotContain("HIDDEN-", "OLD-ORIGINAL", "OLD-REASON", "OLD-EVIDENCE", "CURRENT-CONTENT");
            }
        }
    }
}
