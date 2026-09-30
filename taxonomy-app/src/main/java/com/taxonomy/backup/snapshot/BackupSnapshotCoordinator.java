package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.ArchiveProtectionProvider;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Captures module-owned streams while every controlled installation writer is fenced. */
public final class BackupSnapshotCoordinator {
    private static final BackupComponentId PROOF = new BackupComponentId("capture-proof");
    private final BackupMaintenanceLease barrier;
    private final BackupAuthorizationService authorization;
    private final Inventory inventory;
    private final Map<BackupComponentId, BackupContributor> contributors;
    private final Path root;
    private final CaptureLimits limits;
    private final Clock clock;
    private final ArchiveProtectionProvider protection;

    @FunctionalInterface public interface Inventory { CapturePlan inspect(AuthorizedBackupRequest request) throws IOException; }
    public record CaptureComponent(int version, BackupCompleteness completeness, Set<BackupComponentId> dependencies) {
        public CaptureComponent { new BackupManifest.Component(PROOF, version, completeness, List.of(), dependencies); dependencies = Set.copyOf(dependencies); }
    }
    public record CapturePlan(String applicationVersion, String build, String sourceInstallationId,
                              List<BackupManifest.Repository> repositories, Map<BackupComponentId, CaptureComponent> components,
                              List<String> dependencies, List<String> omissions) {
        public CapturePlan {
            repositories = List.copyOf(repositories); components = Map.copyOf(components);
            dependencies = List.copyOf(dependencies); omissions = List.copyOf(omissions);
            if (components.isEmpty() || components.containsKey(PROOF)) throw new IllegalArgumentException("Invalid capture component inventory");
        }
    }
    public record CaptureLimits(long maxTotalBytes, long maxEntryBytes, int maxEntries, Duration maxDuration, Duration waitForWriters) {
        public CaptureLimits {
            if (maxEntryBytes < 1 || maxTotalBytes < maxEntryBytes || maxEntries < 1 || maxEntries > BackupLimits.MAX_ITEMS
                    || maxDuration.isZero() || maxDuration.isNegative() || maxDuration.compareTo(Duration.ofMinutes(30)) > 0
                    || waitForWriters.isZero() || waitForWriters.isNegative() || waitForWriters.compareTo(Duration.ofMinutes(30)) > 0)
                throw new IllegalArgumentException("Invalid capture limits");
        }
        public static CaptureLimits defaults() { return new CaptureLimits(10L << 30, 2L << 30, BackupLimits.MAX_ITEMS, Duration.ofMinutes(30), Duration.ofSeconds(30)); }
    }

    public BackupSnapshotCoordinator(BackupMaintenanceLease barrier, BackupAuthorizationService authorization,
                                     Inventory inventory, List<BackupContributor> contributors, Path root, CaptureLimits limits, Clock clock) {
        this(barrier, authorization, inventory, contributors, root, limits, clock, ArchiveProtectionProvider.unprotected());
    }

    public BackupSnapshotCoordinator(BackupMaintenanceLease barrier, BackupAuthorizationService authorization,
                                     Inventory inventory, List<BackupContributor> contributors, Path root, CaptureLimits limits, Clock clock,
                                     ArchiveProtectionProvider protection) {
        this.protection = Objects.requireNonNull(protection);
        this.barrier = Objects.requireNonNull(barrier); this.authorization = Objects.requireNonNull(authorization);
        this.inventory = Objects.requireNonNull(inventory); this.root = root.toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits); this.clock = Objects.requireNonNull(clock);
        this.contributors = contributors.stream().collect(Collectors.toUnmodifiableMap(BackupContributor::componentId, c -> c));
    }

    public CapturedBackup capture(AuthorizedBackupRequest creation) throws IOException {
        authorization.requireJobAccess(creation.principalId(), creation);
        if (creation.request().secrets() != SecretsSelection.EXCLUDE && !protection.encrypted()) throw new IllegalArgumentException("Encrypted staging is required for secrets");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup capture cancelled");
        Path staging = null;
        try {
            CapturedBackup captured;
            try (var maintenance = barrier.acquire(creation.request().scope(), limits.waitForWriters())) {
                authorization.requireJobAccess(creation.principalId(), creation);
                Instant started = clock.instant(); long deadline = System.nanoTime() + limits.maxDuration().toNanos();
                BackupId id = BackupId.create(); CapturePlan plan = inventory.inspect(creation);
                var versions = new HashMap<BackupComponentId, Integer>();
                for (var component : plan.components().entrySet()) {
                    var adapter = contributors.get(component.getKey());
                    if (adapter == null || adapter.schemaVersion() != component.getValue().version())
                        throw new IllegalStateException("Missing or unsupported capture component: " + component.getKey().value());
                    versions.put(component.getKey(), component.getValue().version());
                }
                versions.put(PROOF, 1);
                var snapshot = new SnapshotContext(id, creation, started, clock.instant(), maintenance.generation(),
                        plan.repositories().stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::captured)), versions);
                Files.createDirectories(root);
                if (!root.toRealPath().equals(root)) throw new IOException("Capture root must not traverse symlinks");
                staging = Files.getFileStore(root).supportsFileAttributeView("posix")
                        ? Files.createTempDirectory(root, ".capturing-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                        : Files.createTempDirectory(root, ".capturing-");
                var sink = new StagingSink(staging, maintenance, deadline, id, creation.request().secrets());
                var components = new ArrayList<BackupManifest.Component>();
                for (var component : plan.components().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(BackupComponentId::value))).toList()) {
                    sink.check(); int before = sink.entries.size();
                    contributors.get(component.getKey()).write(snapshot, sink);
                    var definition = component.getValue();
                    components.add(new BackupManifest.Component(component.getKey(), definition.version(), definition.completeness(),
                            sink.entries.subList(before, sink.entries.size()).stream().map(BackupEntry::path).toList(), definition.dependencies()));
                }
                sink.check(); Instant completed = clock.instant();
                byte[] proof = JsonMapper.builder().build().writeValueAsBytes(Map.of("barrier", "installation", "generation", maintenance.generation(),
                        "startedAt", started.toString(), "completedAt", completed.toString(), "repositoryCount", plan.repositories().size(),
                        "payloadEntries", sink.entries.size(), "revisions", plan.repositories()));
                var proofEntry = sink.write("verification/capture.json", new ByteArrayInputStream(proof));
                components.add(new BackupManifest.Component(PROOF, 1, BackupCompleteness.COMPLETE, List.of(proofEntry.path()), Set.of()));
                var manifest = new BackupManifest(BackupManifest.FORMAT_VERSION, plan.applicationVersion(), plan.build(), id, plan.sourceInstallationId(),
                        creation.request(), started, completed, CapturedBackup.EVIDENCE_PREFIX + maintenance.generation(), BackupManifest.SUPPORTED_FEATURES,
                        components, plan.repositories(), sink.entries, plan.dependencies(), plan.omissions());
                byte[] document = new BackupManifestCodec().write(manifest);
                if (document.length > limits.maxTotalBytes() - sink.total) throw new IOException("Capture byte limit exceeded");
                try (var channel = FileChannel.open(staging.resolve("manifest.json"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    try (var output = SpoolStreams.output(channel, protection, id, "manifest.json")) { output.write(document); }
                    channel.force(true);
                }
                sink.check(); forceDirectory(staging);
                Path destination = root.resolve("capture-" + id.value());
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                staging = destination; forceDirectory(root);
                // An expired capture is discarded even if a filesystem move already completed.
                maintenance.checkValid();
                captured = CapturedBackup.open(destination, creation, protection);
            }
            return captured;
        } catch (IOException | RuntimeException | Error failure) {
            boolean interrupted = Thread.currentThread().isInterrupted() || failure instanceof InterruptedIOException;
            try { CapturedBackup.delete(staging); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw failure;
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
    }

    private final class StagingSink implements ComponentSink {
        private final Path directory;
        private final BackupMaintenanceLease.Maintenance maintenance;
        private final long deadline;
        private final BackupId id;
        private final SecretsSelection secrets;
        private long validatedAt;
        private long total;
        private final List<BackupEntry> entries = new ArrayList<>();
        private final Set<String> paths = new HashSet<>();
        StagingSink(Path directory, BackupMaintenanceLease.Maintenance maintenance, long deadline, BackupId id, SecretsSelection secrets) {
            this.directory = directory; this.maintenance = maintenance; this.deadline = deadline; this.id = id; this.secrets = secrets;
        }
        void check() throws IOException {
            budget();
            maintenance.renew(); validatedAt = System.nanoTime();
        }
        private void budget() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup capture cancelled");
            long now = System.nanoTime();
            if (now - deadline >= 0) throw new IOException("Capture runtime limit exceeded");
        }
        private void progress() throws IOException {
            budget();
            if (System.nanoTime() - validatedAt >= Duration.ofMillis(100).toNanos()) check();
        }
        @Override public BackupEntry write(String path, InputStream input) throws IOException {
            check();
            if (path.startsWith("protected/") && secrets != SecretsSelection.INCLUDE_ENCRYPTED)
                throw new IOException("Protected payload is outside the authorized selection");
            if (!paths.add(BackupPaths.collisionKey(path))) throw new IllegalArgumentException("Duplicate or colliding capture path");
            if (entries.size() >= limits.maxEntries()) throw new IOException("Capture entry limit exceeded");
            Path target = directory.resolve(path); Files.createDirectories(target.getParent());
            var digest = CapturedBackup.sha256(); long length = 0;
            try (var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                try (var output = SpoolStreams.output(channel, protection, id, path)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        progress();
                        if (read > limits.maxEntryBytes() - length || read > limits.maxTotalBytes() - total) throw new IOException("Capture byte limit exceeded");
                        length += read; total += read; digest.update(buffer, 0, read); output.write(buffer, 0, read);
                    }
                }
                channel.force(true);
            }
            check();
            for (Path parent = target.getParent(); parent.startsWith(directory); parent = parent.getParent()) forceDirectory(parent);
            var entry = new BackupEntry(path, length, HexFormat.of().formatHex(digest.digest())); entries.add(entry); return entry;
        }
    }
}
