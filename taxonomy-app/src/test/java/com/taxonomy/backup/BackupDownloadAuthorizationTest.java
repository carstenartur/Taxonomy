package com.taxonomy.backup;

import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.jobs.*;
import com.taxonomy.backup.snapshot.*;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

/** Real capture, archive, JDBC queue and download boundary; no mocked artifact success. */
class BackupDownloadAuthorizationTest {
    @TempDir Path root;
    final PrincipalId actor = PrincipalId.create();
    final Set<BackupCapability> capabilities = EnumSet.allOf(BackupCapability.class);
    final List<BackupJobState> observed = new ArrayList<>();
    JdbcBackupJobStore store;
    BackupJobStorage storage;
    BackupAuthorizationService authorization;
    BackupMaintenanceLease barrier;
    BackupJobService service;
    JdbcTemplate raw;
    BackupJobId selected;
    boolean enabled = true;
    final ArchiveProtectionProvider protection = ArchiveProtectionProvider.unprotected();
    final BackupRequest request = new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo", "workspace"),
            new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);

    @BeforeEach void prepare() throws Exception {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:download-" + UUID.randomUUID() + ";hsqldb.tx=mvcc"); database.setUser("sa");
        BackupMaintenanceLease.initialize(database); raw = new JdbcTemplate(database);
        barrier = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        store = barrier.jobs(BackupJobLimits.defaults()); storage = new BackupJobStorage(root);
        authorization = new BackupAuthorizationService(new BackupAccessPolicy() {
            public boolean isEnabled(PrincipalId id) { return enabled; }
            public boolean hasCapability(PrincipalId id, BackupCapability capability, BackupScope scope) {
                if (selected != null) store.find(selected).ifPresent(job -> observed.add(job.state()));
                return capabilities.contains(capability);
            }
            public boolean canRead(PrincipalId id, BackupRepositoryKey repository) { return true; }
            public boolean canReadVersion(PrincipalId id, BackupRepositoryKey repository, String commit) { return true; }
        }, Clock.systemUTC());
        service = new BackupJobService(store, authorization, storage, false);
    }

    @Test void workerStreamsEveryStageAndOnlyVerifiedArchiveBecomesDownloadable() throws Exception {
        selected = service.submit(request, actor);
        assertThat(service.status(selected, actor).state()).isEqualTo(BackupJobState.QUEUED);
        assertThatThrownBy(() -> service.download(selected, actor)).isInstanceOf(IllegalStateException.class);
        assertThat(worker(this::capture).runNext()).isTrue();
        var status = service.status(selected, actor);
        assertThat(status.state()).isEqualTo(BackupJobState.READY);
        assertThat(status.downloadAvailable()).isTrue();
        assertThat(observed).contains(BackupJobState.CAPTURING, BackupJobState.WRITING, BackupJobState.VERIFYING);
        var bytes = new ByteArrayOutputStream();
        try (var download = service.download(selected, actor)) {
            download.writeTo(bytes);
            assertThat(download.length()).isEqualTo(bytes.size());
            assertThat(download.filename()).isEqualTo("backup-" + selected.value() + ".taxbackup");
        }
        var downloaded = root.resolve("download.taxbackup"); Files.write(downloaded, bytes.toByteArray());
        try (var verified = new BackupArchiveReader(protection, ArchiveLimits.defaults(), "1.4.0", Map.of(
                new BackupComponentId("records"), 1, new BackupComponentId("capture-proof"), 1)).verify(downloaded)) {
            assertThat(new String(verified.openEntry("data/records.ndjson").readAllBytes(), UTF_8)).isEqualTo("{\"value\":7}\n");
        }
        try (var files = Files.walk(root)) { assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("capture-")); }
        assertThat(worker(this::capture).runNext()).isFalse();
    }

    @Test void foreignOwnerAndRevokedCapabilitiesCannotReadMetadataOrDownload() throws Exception {
        selected = service.submit(request, actor); worker(this::capture).runNext();
        var foreign = PrincipalId.create();
        assertThatThrownBy(() -> service.status(selected, foreign)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.cancel(selected, foreign)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.download(selected, foreign)).isInstanceOf(AccessDeniedException.class);
        capabilities.remove(BackupCapability.DOWNLOAD_BACKUP);
        assertThat(service.status(selected, actor).downloadAvailable()).isFalse();
        assertThatThrownBy(() -> service.download(selected, actor)).isInstanceOf(AccessDeniedException.class);
        capabilities.remove(BackupCapability.EXPORT_CURRENT);
        assertThatThrownBy(() -> service.status(selected, actor)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(actor)).isEmpty();
        enabled = false;
        assertThatThrownBy(() -> service.submit(request, actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void revocationAfterOpeningDownloadStillStopsTransferAndExpiredArtifactCannotOpen() throws Exception {
        selected = service.submit(request, actor); worker(this::capture).runNext();
        try (var download = service.download(selected, actor)) {
            enabled = false; var bytes = new ByteArrayOutputStream();
            assertThatThrownBy(() -> download.writeTo(bytes)).isInstanceOf(AccessDeniedException.class);
            assertThat(bytes.size()).isZero();
        }
        enabled = true;
        raw.update("update backup_job set expires_at=0 where job_id=?", selected.value().toString());
        assertThatThrownBy(() -> service.download(selected, actor)).isInstanceOf(IllegalStateException.class);
        worker(this::capture).cleanup();
        assertThat(store.find(selected)).isEmpty();
        assertEmptyStorage();
    }

    @Test void cancellationDuringCaptureStopsTheStreamAndCleansPrivateFiles() throws Exception {
        selected = service.submit(request, actor);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        BackupCaptureSource source = (creation, directory, progress) -> capture(creation, directory, progress, () -> new InputStream() {
            public int read() { return 0; }
            public int read(byte[] bytes, int offset, int length) {
                if (reads.incrementAndGet() == 3) service.cancel(selected, actor);
                Arrays.fill(bytes, offset, offset + length, (byte) 42); return length;
            }
        });
        try {
            worker(source).runNext();
            assertThat(store.find(selected).orElseThrow().state()).isEqualTo(BackupJobState.CANCELLED);
            assertThat(reads.get()).isLessThan(100);
            assertEmptyStorage();
        } finally { Thread.interrupted(); }
    }

    @Test void failuresExposeOnlyCodesAndLostWorkersCannotPublishOrLeaveRecoverableSuccess() throws Exception {
        selected = service.submit(request, actor);
        worker((creation, directory, progress) -> { throw new IOException("password=do-not-persist /private/customer/source"); }).runNext();
        String status = new tools.jackson.databind.json.JsonMapper().writeValueAsString(service.status(selected, actor));
        assertThat(status).contains("CAPTURE_FAILED").doesNotContain("password", "customer", "source", "decisionId", "principalId", "owner", "authorization");
        assertEmptyStorage();
        selected = service.submit(request, actor);
        var lost = store.claim(UUID.randomUUID()).orElseThrow();
        Files.writeString(storage.workDirectory(lost).resolve("partial"), "unfinished");
        raw.update("update backup_job set lease_until=0 where job_id=?", selected.value().toString());
        worker(this::capture).cleanup();
        assertThat(store.find(selected).orElseThrow().failure()).isEqualTo(BackupJobFailure.WORKER_EXPIRED);
        assertEmptyStorage();
        assertThatThrownBy(() -> service.download(selected, actor)).isInstanceOf(IllegalStateException.class);
    }

    @Test void artifactReplacementAndCorruptionAreRejectedBeforeAnyDownload() throws Exception {
        selected = service.submit(request, actor); worker(this::capture).runNext();
        var receipt = store.find(selected).orElseThrow().artifact();
        var artifact = storage.artifactPath(receipt);
        Files.write(artifact, new byte[(int) receipt.length()]);
        assertThatThrownBy(() -> service.download(selected, actor)).isInstanceOf(IOException.class);
    }

    private void assertEmptyStorage() throws IOException {
        try (var files = Files.list(root)) { assertThat(files).isEmpty(); }
    }

    private BackupJobWorker worker(BackupCaptureSource source) {
        return new BackupJobWorker(store, authorization, storage, source, new BackupArchiveWriter(protection, ArchiveLimits.defaults()));
    }
    private CapturedBackup capture(AuthorizedBackupRequest creation, Path directory, ArchiveProgress progress) throws IOException {
        return capture(creation, directory, progress, () -> new ByteArrayInputStream("{\"value\":7}\n".getBytes(UTF_8)));
    }
    @FunctionalInterface interface Input { InputStream open() throws IOException; }
    private CapturedBackup capture(AuthorizedBackupRequest creation, Path directory, ArchiveProgress progress, Input input) throws IOException {
        var component = new BackupComponentId("records");
        var repositories = List.of(new BackupManifest.Repository(new BackupRepositoryKey("repo", "workspace"), "opaque", GitRepresentation.NONE,
                new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of()), null, null, null));
        var plan = new BackupSnapshotCoordinator.CapturePlan("1.4.0", "test", "installation", repositories,
                Map.of(component, new BackupSnapshotCoordinator.CaptureComponent(1, BackupCompleteness.COMPLETE, Set.of())), List.of(), List.of());
        BackupContributor contributor = new BackupContributor() {
            public BackupComponentId componentId() { return component; }
            public int schemaVersion() { return 1; }
            public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
                try (var stream = input.open()) { sink.write("data/records.ndjson", stream); }
            }
        };
        return new BackupSnapshotCoordinator(barrier, authorization, ignored -> plan, List.of(contributor), directory,
                BackupSnapshotCoordinator.CaptureLimits.defaults(), Clock.systemUTC(), protection).capture(creation, progress);
    }
}
