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
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test void generatedBytesAreHashedOnTheCaptureThreadAndEscapedStreamsCannotChangeThem() throws Exception {
        byte[] bytes = new byte[262144]; new Random(19).nextBytes(bytes);
        byte[] expected = new byte[200001]; expected[0] = (byte) 165; System.arraycopy(bytes, 3, expected, 1, 200000);
        var owner = Thread.currentThread(); var escaped = new OutputStream[1]; var receipt = new BackupEntry[1];
        String path = "data/records/generated.pack";
        var creation = authorization.authorize(actor, request);
        try (var captured = coordinator((snapshot, sink) -> receipt[0] = sink.writeGenerated(path, output -> {
            assertThat(Thread.currentThread()).isSameAs(owner); escaped[0] = output;
            assertThatThrownBy(() -> secondNode.update("update evidence set revision=8")).hasMessageContaining("maintenance");
            output.write(165); output.write(bytes, 3, 200000); output.write(bytes, 0, 0); output.flush(); output.close();
        })).capture(creation)) {
            assertThat(receipt[0]).isEqualTo(new BackupEntry(path, expected.length,
                    HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(expected))));
            assertThat(captured.manifest().entries()).contains(receipt[0]);
            assertThatThrownBy(() -> escaped[0].write(42)).isInstanceOf(IOException.class);
            try (var reopened = CapturedBackup.open(captured.directory(), creation); var input = reopened.openEntry(path)) {
                assertThat(input.readAllBytes()).isEqualTo(expected);
            }
        }
        assertEmpty();
    }

    @Test void aSwallowedGeneratedByteLimitFailurePoisonsTheCapture() {
        var producerRan = new boolean[1]; var swallowed = new IOException[1];
        var coordinator = new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan(),
                List.of(contributor((snapshot, sink) -> sink.writeGenerated("data/records/large.pack", output -> {
                    producerRan[0] = true;
                    try { output.write(new byte[65]); } catch (IOException failure) { swallowed[0] = failure; }
                }))), directory, new CaptureLimits(4096, 64, 20, Duration.ofSeconds(5), Duration.ofSeconds(2)), Clock.systemUTC());
        var failure = catchThrowable(() -> coordinator.capture(authorization.authorize(actor, request)));
        assertThat(producerRan[0]).isTrue(); assertThat(swallowed[0]).hasMessageContaining("limit");
        assertThat(failure).isInstanceOf(IOException.class); assertEmpty();
        assertThatCode(() -> secondNode.update("update evidence set revision=3")).doesNotThrowAnyException();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void generatedAndInputEntriesShareTotalByteAndEntryBudgets(boolean entryCount) {
        var limits = new CaptureLimits(entryCount ? 16384 : 1536, 1024, entryCount ? 1 : 20, Duration.ofSeconds(5), Duration.ofSeconds(2));
        var coordinator = new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan(),
                List.of(contributor((snapshot, sink) -> {
                    sink.write("data/records/first", new ByteArrayInputStream(new byte[1024]));
                    sink.writeGenerated("data/records/second", output -> output.write(new byte[1024]));
                })), directory, limits, Clock.systemUTC());
        assertThatThrownBy(() -> coordinator.capture(authorization.authorize(actor, request))).isInstanceOf(IOException.class).hasMessageContaining("limit");
        assertEmpty();
    }

    @Test void failedGeneratedProducerCannotPublishPartialBytes() {
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> sink.writeGenerated("data/records/partial", output -> {
            output.write(new byte[]{1, 2, 3}); throw new IOException("Producer failed");
        })).capture(authorization.authorize(actor, request))).isInstanceOf(IOException.class).hasMessage("Producer failed");
        assertEmpty(); assertThatCode(() -> secondNode.update("update evidence set revision=3")).doesNotThrowAnyException();
    }

    @Test void swallowedGeneratedCancellationStillAbortsAndPreservesInterruption() {
        try {
            assertThatThrownBy(() -> coordinator((snapshot, sink) -> sink.writeGenerated("data/records/cancelled", output -> {
                output.write(1); Thread.currentThread().interrupt();
                try { output.write(2); } catch (IOException ignored) { }
            })).capture(authorization.authorize(actor, request))).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertEmpty(); assertThat(raw.queryForObject("select phase from backup_barrier_state", Integer.class)).isZero();
    }

    @Test void generatedCaptureCannotPublishAfterItsLeaseWasFenced() {
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> sink.writeGenerated("data/records/fenced", output -> {
            output.write(1); raw.update("update backup_barrier_state set expires_at=0");
            secondNode.update("update evidence set revision=4"); output.write(2);
        })).capture(authorization.authorize(actor, request))).hasStackTraceContaining("fenced");
        assertEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void generatedProducerCannotHideNestedEntriesFromEntryLimits(boolean inputEntry) {
        var nested = new IOException[1];
        var failure = catchThrowable(() -> coordinator((snapshot, sink) -> sink.writeGenerated("data/records/outer", output -> {
            output.write(1);
            try {
                if (inputEntry) sink.write("data/records/inner", new ByteArrayInputStream(new byte[]{2}));
                else sink.writeGenerated("data/records/inner", inner -> inner.write(2));
            }
            catch (IOException rejected) { nested[0] = rejected; }
        })).capture(authorization.authorize(actor, request)));
        assertThat(nested[0]).hasMessageContaining("nested"); assertThat(failure).isInstanceOf(IOException.class); assertEmpty();
    }

    enum ForeignCall { OUTPUT_WRITE, OUTPUT_FLUSH, OUTPUT_CLOSE, SINK_INPUT, SINK_GENERATED, CHECKPOINT }
    @ParameterizedTest @EnumSource(ForeignCall.class)
    void generatedOutputRejectsForeignThreadsAndPoisonsTheCapture(ForeignCall call) {
        var foreign = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var failure = catchThrowable(() -> coordinator((snapshot, sink) -> sink.writeGenerated("data/records/thread", output -> {
            var worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    switch (call) {
                        case OUTPUT_WRITE -> output.write(1);
                        case OUTPUT_FLUSH -> output.flush();
                        case OUTPUT_CLOSE -> output.close();
                        case SINK_INPUT -> sink.write("data/records/foreign", new ByteArrayInputStream(new byte[]{1}));
                        case SINK_GENERATED -> sink.writeGenerated("data/records/foreign", target -> target.write(1));
                        case CHECKPOINT -> sink.checkpoint();
                    }
                } catch (Throwable rejected) { foreign.set(rejected); }
            });
            try { worker.join(5000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            assertThat(worker.isAlive()).isFalse();
        })).capture(authorization.authorize(actor, request)));
        assertThat(foreign.get()).isInstanceOf(IOException.class); assertThat(failure).isInstanceOf(IOException.class); assertEmpty();
    }

    @Test void generatedPathsAndProtectedNamespacesAreCheckedBeforeCallingTheProducer() {
        for (String path : List.of("../escape", "protected/credentials")) {
            var called = new boolean[1];
            var failure = catchThrowable(() -> coordinator((snapshot, sink) -> sink.writeGenerated(path, output -> called[0] = true))
                    .capture(authorization.authorize(actor, request)));
            assertThat(called[0]).isFalse();
            assertThat(failure).isInstanceOf(path.startsWith("protected/") ? IOException.class : IllegalArgumentException.class);
        }
        assertEmpty();
    }

    @Test void generatedAndInputEntriesSharePathCollisionChecks() {
        var called = new boolean[1];
        assertThatThrownBy(() -> coordinator((snapshot, sink) -> {
            write(sink, "data/records/File", "one");
            sink.writeGenerated("data/records/file", output -> called[0] = true);
        }).capture(authorization.authorize(actor, request))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("path");
        assertThat(called[0]).isFalse(); assertEmpty();
    }

    @Test void generatedProtectedEntriesStayEncryptedInEveryDurableSpoolFile() throws Exception {
        com.google.crypto.tink.streamingaead.StreamingAeadConfig.register();
        var protection = new com.taxonomy.backup.archive.TinkArchiveProtection(com.google.crypto.tink.KeysetHandle.generateNew(
                com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB));
        var secret = "GENERATED-SECRET-" + UUID.randomUUID(); var path = "protected/generated.pack";
        var request = new BackupRequest(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation(), new BackupTime.History(),
                GitRepresentation.BUNDLE, SecretsSelection.INCLUDE_ENCRYPTED);
        var creation = authorization.authorize(actor, request); var base = plan();
        var coordinator = new BackupSnapshotCoordinator(barrier, authorization, ignored -> new CapturePlan(base.applicationVersion(), base.build(),
                base.sourceInstallationId(), List.of(), base.components(), base.dependencies(), base.omissions()),
                List.of(contributor((snapshot, sink) -> sink.writeGenerated(path, output -> output.write(secret.getBytes(UTF_8))))),
                directory, CaptureLimits.defaults(), Clock.systemUTC(), protection);
        try (var captured = coordinator.capture(creation)) {
            try (var files = Files.walk(captured.directory())) {
                for (var file : files.filter(Files::isRegularFile).toList()) assertThat(new String(Files.readAllBytes(file), UTF_8)).doesNotContain(secret);
            }
            try (var reopened = CapturedBackup.open(captured.directory(), creation, protection); var input = reopened.openEntry(path)) {
                assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo(secret);
            }
        }
        assertEmpty();
    }

    @Test void anInputOnlySinkRefusesGeneratedEntriesBeforeCallingTheProducer() {
        ComponentSink sink = (path, input) -> { throw new AssertionError("Must not buffer generated output into a legacy sink"); };
        assertThatThrownBy(() -> sink.writeGenerated("data/records/unsupported", output -> fail("Producer must not be called")))
                .isInstanceOf(IOException.class).hasMessageContaining("generated");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void returnedGeneratedStreamsAreInvalidatedEvenWhenTheProducerDidNotCloseThem(boolean flush) {
        var escaped = new OutputStream[1]; var rejected = new IOException[1];
        var failure = catchThrowable(() -> coordinator((snapshot, sink) -> {
            sink.writeGenerated("data/records/closed-scope", output -> { escaped[0] = output; output.write(1); });
            try { if (flush) escaped[0].flush(); else escaped[0].write(2); }
            catch (IOException closed) { rejected[0] = closed; }
        }).capture(authorization.authorize(actor, request)));
        assertThat(rejected[0]).isNotNull(); assertThat(failure).isInstanceOf(IOException.class); assertEmpty();
    }

    @Test void aSingleLargeGeneratedWriteCanBeCancelledBeforeAllItsBytesAreWritten() {
        byte[] bytes = new byte[1024 * 1024]; long[] observed = { 0 };
        try {
            assertThatThrownBy(() -> coordinator((snapshot, sink) -> sink.writeGenerated("data/records/large", output -> {
                try { output.write(bytes); } catch (IOException ignored) { }
            })).capture(authorization.authorize(actor, request), count -> {
                if (count > 0) { observed[0] = count; throw new InterruptedIOException("Stop generated content"); }
            })).isInstanceOf(InterruptedIOException.class);
            assertThat(observed[0]).isPositive().isLessThan(bytes.length); assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertEmpty();
    }

    enum SwallowedEntryFailure { PRODUCER_IO, PRODUCER_RUNTIME, PRODUCER_ERROR, INVALID_PATH, DUPLICATE_PATH, PROTECTED_PATH, SPOOL_CLOSE }
    @ParameterizedTest @EnumSource(SwallowedEntryFailure.class)
    void swallowedFailuresAtAnyEntryBoundaryPreventLaterPublication(SwallowedEntryFailure kind) {
        var caught = new Throwable[2]; String ordinary = "data/records/rejected";
        var protection = new com.taxonomy.backup.archive.ArchiveProtectionProvider() {
            public boolean encrypted() { return false; }
            public InputStream unprotect(InputStream input, byte[] context) { return input; }
            public java.nio.channels.SeekableByteChannel open(java.nio.channels.SeekableByteChannel input, byte[] context) { return input; }
            public OutputStream protect(OutputStream output, byte[] context) {
                if (kind != SwallowedEntryFailure.SPOOL_CLOSE || !new String(context, UTF_8).endsWith(ordinary)) return output;
                return new FilterOutputStream(output) {
                    @Override public void close() throws IOException { super.close(); throw new IOException("Spool finalization failed"); }
                };
            }
        };
        var coordinator = new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan(), List.of(contributor((snapshot, sink) -> {
            if (kind == SwallowedEntryFailure.DUPLICATE_PATH) write(sink, ordinary, "existing");
            String path = kind == SwallowedEntryFailure.INVALID_PATH ? "../escape" : kind == SwallowedEntryFailure.PROTECTED_PATH ? "protected/rejected" : ordinary;
            caught[0] = catchThrowable(() -> sink.writeGenerated(path, output -> {
                output.write(1);
                switch (kind) {
                    case PRODUCER_IO -> throw new IOException("Producer failed");
                    case PRODUCER_RUNTIME -> throw new IllegalStateException("Producer failed");
                    case PRODUCER_ERROR -> throw new AssertionError("Producer failed");
                    default -> { }
                }
            }));
            caught[1] = catchThrowable(sink::checkpoint);
        })), directory, CaptureLimits.defaults(), Clock.systemUTC(), protection);
        var failure = catchThrowable(() -> coordinator.capture(authorization.authorize(actor, request)));
        assertThat(caught[0]).isNotNull(); assertThat(caught[1]).isInstanceOf(IOException.class);
        assertThat(failure).isInstanceOf(IOException.class); assertEmpty();
    }

    enum ProgressBoundary { PRODUCER_END, FINAL_PUBLICATION }
    @ParameterizedTest @EnumSource(ProgressBoundary.class)
    void progressCallbacksCannotUseExpiredStreamsOrHideTheirFailure(ProgressBoundary boundary) {
        var escaped = new OutputStream[1]; var completed = new boolean[1]; var attempted = new boolean[1]; var rejected = new IOException[1];
        var coordinator = coordinator((snapshot, sink) -> sink.writeGenerated("data/records/generated", output -> {
            escaped[0] = output; output.write(1); completed[0] = true;
        }));
        var failure = catchThrowable(() -> {
            try (var captured = coordinator.capture(authorization.authorize(actor, request), bytes -> {
                if (attempted[0] || !completed[0]) return;
                if (boundary == ProgressBoundary.FINAL_PUBLICATION) {
                    try (var files = Files.walk(directory)) {
                        if (files.noneMatch(file -> file.getFileName().toString().equals("manifest.json"))) return;
                    }
                }
                attempted[0] = true;
                try { escaped[0].write(2); } catch (IOException closed) { rejected[0] = closed; }
            })) { assertThat(captured.manifest()).isNotNull(); }
        });
        assertThat(attempted[0]).isTrue(); assertThat(rejected[0]).isNotNull();
        assertThat(failure).isInstanceOf(IOException.class); assertEmpty();
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
