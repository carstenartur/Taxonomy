package com.taxonomy.backup;

import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.backup.snapshot.BackupSnapshotCoordinator;
import com.taxonomy.backup.snapshot.BackupSnapshotCoordinator.*;
import com.taxonomy.backup.snapshot.CapturedBackup;
import com.taxonomy.backup.snapshot.GuardedBackupDataSource;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackupSnapshotCoordinatorTest {
    @TempDir Path directory;
    private static final BackupComponentId COMPONENT = new BackupComponentId("records");
    private final BackupRepositoryKey repository = new BackupRepositoryKey("repo", "workspace");
    private final PrincipalId actor = PrincipalId.create();
    private final BackupRequest request = new BackupRequest(BackupProfile.CURRENT_STATE,
            new BackupScope.Workspace("repo", "workspace"), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
    private final BackupAccessPolicy access = mock(BackupAccessPolicy.class);
    private final BackupAuthorizationService authorization = new BackupAuthorizationService(access, Clock.systemUTC());
    private BackupMaintenanceLease barrier;
    private JdbcTemplate raw;
    private JdbcTemplate secondNode;

    @BeforeEach void setUp() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:snapshot-" + UUID.randomUUID() + ";hsqldb.tx=mvcc"); database.setUser("sa");
        BackupMaintenanceLease.initialize(database);
        barrier = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        raw = new JdbcTemplate(database); raw.execute("create table evidence (revision integer)"); raw.update("insert into evidence values (1)");
        secondNode = new JdbcTemplate(new GuardedBackupDataSource(database, new BackupMaintenanceLease(database, Duration.ofSeconds(30))));
        when(access.isEnabled(actor)).thenReturn(true);
        when(access.hasCapability(eq(actor), any(), any())).thenReturn(true);
        when(access.canRead(actor, repository)).thenReturn(true);
    }

    @Test void stagesBytesAndRevisionsUnderOneLeaseThenCanReopenWithoutTheSource() throws Exception {
        var creation = authorization.authorize(actor, request);
        var coordinator = coordinator((snapshot, sink) -> {
            assertThat(snapshot.fencingGeneration()).isPositive();
            assertThatThrownBy(() -> secondNode.update("update evidence set revision=2")).hasMessageContaining("maintenance");
            write(sink, "data/records/current.ndjson", raw.queryForObject("select revision from evidence", String.class) + "\n");
        });
        try (var captured = coordinator.capture(creation)) {
            assertThat(captured.snapshot().authorization().principalId()).isEqualTo(actor);
            assertThat(captured.manifest().consistencyEvidence()).contains("writer-barrier-v1", "installation");
            assertThat(captured.manifest().components()).extracting(BackupManifest.Component::id).contains(COMPONENT);
            assertThat(captured.manifest().entries()).extracting(BackupEntry::path).contains("verification/capture.json");
            secondNode.update("update evidence set revision=2");
            var reopened = CapturedBackup.open(captured.directory(), creation);
            assertThat(reopened.snapshot()).isEqualTo(captured.snapshot());
            try (var data = reopened.openEntry("data/records/current.ndjson")) {
                assertThat(new String(data.readAllBytes(), UTF_8)).isEqualTo("1\n");
            }
            try (var files = Files.list(directory)) {
                assertThat(files.map(p -> p.getFileName().toString())).allMatch(n -> n.startsWith("capture-"));
            }
        }
        assertEmpty();
    }

    @Test void revokedAccessIsCheckedAgainBeforeAcquiringOrStaging() {
        var creation = authorization.authorize(actor, request);
        when(access.canRead(actor, repository)).thenReturn(false);
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> fail("Contributor must not run")).capture(creation))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(raw.queryForObject("select phase from backup_barrier_state", Integer.class)).isZero();
        assertEmpty();
    }

    @Test void failedContributorLeavesNeitherCompleteNorPartialCapture() {
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> {
            write(sink, "data/records/one.ndjson", "one"); throw new IOException("Contributor interrupted");
        }).capture(authorization.authorize(actor, request))).isInstanceOf(IOException.class);
        assertEmpty();
        assertThatCode(() -> secondNode.update("update evidence set revision=3")).doesNotThrowAnyException();
    }

    @Test void expiredCaptureCannotPublishAfterAnotherNodeResumesWrites() {
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> {
            write(sink, "data/records/one.ndjson", "one");
            raw.update("update backup_barrier_state set expires_at=0");
            secondNode.update("update evidence set revision=4");
        }).capture(authorization.authorize(actor, request))).hasStackTraceContaining("fenced");
        assertEmpty();
    }

    @Test void cancellationAndOversizedEntriesAreBoundedAndCleanedUp() {
        try {
            assertThatThrownBy(() -> coordinator((snapshot, sink) -> {
                Thread.currentThread().interrupt(); write(sink, "data/records/one.ndjson", "one");
            }).capture(authorization.authorize(actor, request))).isInstanceOf(IOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertEmpty();
        var bounded = new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan(),
                List.of(contributor((snapshot, sink) -> write(sink, "data/records/large.ndjson", "x".repeat(65)))), directory,
                new CaptureLimits(1024, 64, 20, Duration.ofSeconds(5), Duration.ofSeconds(2)), Clock.systemUTC());
        assertThatThrownBy(() -> bounded.capture(authorization.authorize(actor, request))).hasMessageContaining("limit");
        assertEmpty();
    }

    @Test void undeclaredComponentsDuplicatePathsAndPathEscapesCannotProduceACapture() {
        var missing = new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan(), List.of(), directory, CaptureLimits.defaults(), Clock.systemUTC());
        assertThatThrownBy(() -> missing.capture(authorization.authorize(actor, request))).hasMessageContaining("component");
        for (String path : List.of("../escape", "/absolute", "data/records/../escape", "data/records/CON", "data/records/a\\b")) {
            assertThatThrownBy(() -> coordinator((snapshot, sink) -> write(sink, path, "bad"))
                    .capture(authorization.authorize(actor, request))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> {
            write(sink, "data/records/File.ndjson", "one"); write(sink, "data/records/file.ndjson", "two");
        }).capture(authorization.authorize(actor, request))).hasMessageContaining("path");
        assertEmpty();
    }

    @Test void sourceChangesToStagedFilesAreDetectedBeforeArchivePublication() throws Exception {
        try (var captured = coordinator((snapshot, sink) -> write(sink, "data/records/current.ndjson", "one"))
                .capture(authorization.authorize(actor, request))) {
            Files.writeString(captured.directory().resolve("data/records/current.ndjson"), "two");
            assertThatThrownBy(() -> {
                try (var input = captured.openEntry("data/records/current.ndjson")) { input.transferTo(OutputStream.nullOutputStream()); }
            }).isInstanceOf(IOException.class).hasMessageContaining("digest");
        }
    }

    private BackupSnapshotCoordinator coordinator(Write action) {
        return new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan(), List.of(contributor(action)), directory, CaptureLimits.defaults(), Clock.systemUTC());
    }
    private CapturePlan plan() {
        var state = new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of());
        return new CapturePlan("1.4.0-SNAPSHOT", "test-build", "installation-id",
                List.of(new BackupManifest.Repository(repository, "opaque-repo", GitRepresentation.NONE, state, null, null, null)),
                Map.of(COMPONENT, new CaptureComponent(1, BackupCompleteness.COMPLETE, Set.of())), List.of(), List.of());
    }
    private static BackupContributor contributor(Write action) {
        return new BackupContributor() {
            public BackupComponentId componentId() { return COMPONENT; }
            public int schemaVersion() { return 1; }
            public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException { action.run(snapshot, sink); }
        };
    }
    private static void write(ComponentSink sink, String path, String value) throws IOException {
        try (var input = new ByteArrayInputStream(value.getBytes(UTF_8))) { sink.write(path, input); }
    }
    private void assertEmpty() {
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @FunctionalInterface private interface Write { void run(SnapshotContext snapshot, ComponentSink sink) throws IOException; }
}
