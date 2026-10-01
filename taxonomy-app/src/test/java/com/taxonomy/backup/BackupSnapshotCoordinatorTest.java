package com.taxonomy.backup;

import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import com.taxonomy.backup.snapshot.BackupSnapshotCoordinator;
import com.taxonomy.backup.snapshot.BackupSnapshotCoordinator.*;
import com.taxonomy.backup.snapshot.CapturedBackup;
import com.taxonomy.backup.snapshot.GuardedBackupDataSource;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    @Test void cancellationDuringPreflightIsObservedBeforeAnyEntryIsWritten() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try {
            assertThatThrownBy(() -> coordinator((snapshot, sink) -> {
                for (int i = 0; i < 100; i++) sink.checkpoint();
                fail("Cancelled preflight must not reach entry writing");
            }).capture(authorization.authorize(actor, request), bytes -> {
                if (checks.incrementAndGet() == 10) throw new InterruptedIOException("Cancelled");
            })).isInstanceOf(InterruptedIOException.class);
        } finally { Thread.interrupted(); }
        assertEmpty();
        assertThat(raw.queryForObject("select phase from backup_barrier_state", Integer.class)).isZero();
        assertThatCode(() -> secondNode.update("update evidence set revision=5")).doesNotThrowAnyException();
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

    @ParameterizedTest
    @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "INSTALLATION_CURRENT"})
    void retainedManifestIncludesSelectedAdaptersProfileOmissionsAndReviewedExclusions(BackupProfile profile) throws Exception {
        var events = new ArrayList<String>();
        var adapter = dataContributor(COMPONENT, selected -> {
            assertThat(selected).isEqualTo(profile);
            events.add("omissions");
            return List.of("shared omission", "adapter: " + selected);
        }, (snapshot, sink) -> {
            events.add("write");
            write(sink, "data/records/current.ndjson", "current");
        });
        var inventory = new BackupInventory(List.of(
                new BackupInventory.Category("records", COMPONENT, BackupStorageRule.PORTABLE_PRIMARY, "Current records"),
                new BackupInventory.Category("lease", COMPONENT, BackupStorageRule.TRANSIENT, "Never reactivate workers")));
        var composition = new CompositeBackupDataContributor(COMPONENT, 1, inventory, List.of(adapter));
        var firstId = new BackupComponentId("a-records");
        var first = dataContributor(firstId, selected -> {
            assertThat(selected).isEqualTo(profile); events.add("first omissions");
            return List.of("first adapter omission");
        }, (snapshot, sink) -> {
            events.add("first write"); write(sink, "data/a-records/current.ndjson", "first");
        });
        var unselected = dataContributor(new BackupComponentId("unselected"), ignored -> {
            throw new AssertionError("Unselected component metadata was inspected");
        }, (snapshot, sink) -> fail("Unselected component was captured"));
        CapturePlan source = plan();
        var components = new HashMap<>(source.components());
        components.put(firstId, new CaptureComponent(1, BackupCompleteness.COMPLETE, Set.of()));
        var coordinator = new BackupSnapshotCoordinator(barrier, authorization,
                ignored -> new CapturePlan(source.applicationVersion(), source.build(), source.sourceInstallationId(), source.repositories(),
                        components, source.dependencies(), List.of("plan omission", "shared omission")),
                List.of(unselected, composition, first), directory, CaptureLimits.defaults(), Clock.systemUTC());
        var selectedRequest = new BackupRequest(profile, profile.isInstallation() ? new BackupScope.Installation() : request.scope(),
                new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        var creation = authorization.authorize(actor, selectedRequest);
        try (var captured = coordinator.capture(creation)) {
            assertThat(captured.manifest().omissions()).containsExactly("plan omission", "shared omission",
                    "first adapter omission", "lease: excluded (TRANSIENT); Never reactivate workers", "adapter: " + profile);
            try (var reopened = CapturedBackup.open(captured.directory(), creation)) {
                assertThat(reopened.manifest().omissions()).isEqualTo(captured.manifest().omissions());
            }
        }
        assertThat(events).containsExactly("first omissions", "omissions", "first write", "write");
        assertEmpty();
    }

    @Test void unavailableOmissionMetadataAbortsBeforeSourceCaptureAndReleasesTheLease() {
        var firstId = new BackupComponentId("a-records");
        var first = dataContributor(firstId, profile -> List.of("first adapter omission"),
                (snapshot, sink) -> fail("Earlier component must not write before later metadata is collected"));
        var adapter = dataContributor(COMPONENT, profile -> { throw new IllegalStateException("Omissions unavailable"); },
                (snapshot, sink) -> fail("Sources must not be captured with missing omission metadata"));
        CapturePlan source = plan();
        var components = new HashMap<>(source.components());
        components.put(firstId, new CaptureComponent(1, BackupCompleteness.COMPLETE, Set.of()));
        var coordinator = new BackupSnapshotCoordinator(barrier, authorization, ignored -> new CapturePlan(source.applicationVersion(),
                source.build(), source.sourceInstallationId(), source.repositories(), components, source.dependencies(), source.omissions()),
                List.of(adapter, first), directory, CaptureLimits.defaults(), Clock.systemUTC());
        assertThatThrownBy(() -> coordinator.capture(authorization.authorize(actor, request)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Omissions unavailable");
        assertEmpty();
        assertThatCode(() -> secondNode.update("update evidence set revision=5")).doesNotThrowAnyException();
    }

    private static BackupDataContributor dataContributor(BackupComponentId id,
            java.util.function.Function<BackupProfile, List<String>> omissions, Write action) {
        return new BackupDataContributor() {
            @Override public BackupComponentId componentId() { return id; }
            @Override public int schemaVersion() { return 1; }
            @Override public Set<String> categories() { return Set.of("records"); }
            @Override public List<String> omissions(BackupProfile profile) { return omissions.apply(profile); }
            @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException { action.run(snapshot, sink); }
        };
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
