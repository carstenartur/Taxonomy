package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.ArchiveProtectionProvider;
import com.taxonomy.backup.archive.ArchiveProgress;
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

    @FunctionalInterface public interface Inventory {
        CapturePlan inspect(AuthorizedBackupRequest request) throws IOException;
        /** Override for discovery that performs substantial I/O; keep the capture fence and budgets live. */
        default CapturePlan inspect(AuthorizedBackupRequest request, BackupCheckpoint checkpoint) throws IOException {
            checkpoint.check(); var plan = inspect(request); checkpoint.check(); return plan;
        }
    }
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
        return capture(creation, ArchiveProgress.NONE);
    }

    public CapturedBackup capture(AuthorizedBackupRequest creation, ArchiveProgress progress) throws IOException {
        Objects.requireNonNull(progress).check(0);
        authorization.requireJobAccess(creation.principalId(), creation);
        if (creation.request().secrets() != SecretsSelection.EXCLUDE && !protection.encrypted()) throw new IllegalArgumentException("Encrypted staging is required for secrets");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup capture cancelled");
        Path staging = null;
        try {
            CapturedBackup captured;
            try (var maintenance = barrier.acquire(creation.request().scope(), limits.waitForWriters())) {
                authorization.requireJobAccess(creation.principalId(), creation);
                Instant started = clock.instant(); long deadline = System.nanoTime() + limits.maxDuration().toNanos();
                BackupId id = BackupId.create();
                Files.createDirectories(root);
                if (!root.toRealPath().equals(root)) throw new IOException("Capture root must not traverse symlinks");
                staging = Files.getFileStore(root).supportsFileAttributeView("posix")
                        ? Files.createTempDirectory(root, ".capturing-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                        : Files.createTempDirectory(root, ".capturing-");
                var sink = new StagingSink(staging, maintenance, deadline, id, creation.request().secrets(), progress);
                sink.check(); CapturePlan plan = inventory.inspect(creation, sink::checkpoint); sink.check();
                var versions = new HashMap<BackupComponentId, Integer>();
                var omissions = new LinkedHashSet<>(plan.omissions());
                var plannedComponents = plan.components().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(BackupComponentId::value))).toList();
                for (var component : plannedComponents) {
                    var adapter = contributors.get(component.getKey());
                    if (adapter == null || adapter.schemaVersion() != component.getValue().version())
                        throw new IllegalStateException("Missing or unsupported capture component: " + component.getKey().value());
                    versions.put(component.getKey(), component.getValue().version());
                    if (adapter instanceof BackupDataContributor data)
                        omissions.addAll(data.omissions(creation.request().profile()));
                }
                versions.put(PROOF, 1);
                var snapshot = new SnapshotContext(id, creation, started, clock.instant(), maintenance.generation(),
                        plan.repositories().stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::captured)), versions,
                        plan.repositories().stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::archiveId)),
                        plan.repositories().stream().filter(repository -> repository.exportedHead() != null)
                                .collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::exportedHead)));
                var components = new ArrayList<BackupManifest.Component>();
                for (var component : plannedComponents) {
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
                        components, plan.repositories(), sink.entries, plan.dependencies(), List.copyOf(omissions));
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
        private final ArchiveProgress observer;
        private long validatedAt;
        private long total;
        private final Thread captureThread = Thread.currentThread();
        private volatile boolean entryFailed;
        private boolean entryOpen;
        private final List<BackupEntry> entries = new ArrayList<>();
        private final Set<String> paths = new HashSet<>();
        StagingSink(Path directory, BackupMaintenanceLease.Maintenance maintenance, long deadline, BackupId id, SecretsSelection secrets, ArchiveProgress observer) {
            this.directory = directory; this.maintenance = maintenance; this.deadline = deadline; this.id = id; this.secrets = secrets; this.observer = observer;
        }
        void check() throws IOException {
            budget();
            maintenance.renew(); validatedAt = System.nanoTime();
        }
        private void requireCaptureThread() throws IOException {
            if (Thread.currentThread() != captureThread) {
                entryFailed = true; throw new IOException("Capture writes require the capture thread");
            }
        }
        private void checkBudgetState() throws IOException {
            requireCaptureThread();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup capture cancelled");
            if (entryFailed) throw new IOException("A capture entry previously failed");
            long now = System.nanoTime();
            if (now - deadline >= 0) throw new IOException("Capture runtime limit exceeded");
        }
        private void budget() throws IOException {
            checkBudgetState();
            observer.check(total);
            checkBudgetState();
        }
        private void progress() throws IOException {
            budget();
            if (System.nanoTime() - validatedAt >= Duration.ofMillis(100).toNanos()) check();
        }
        @Override public void checkpoint() throws IOException {
            try { progress(); }
            catch (IOException | RuntimeException | Error failure) { poison(failure); throw failure; }
        }
        @Override public BackupEntry write(String path, InputStream input) throws IOException {
            return writeGenerated(path, output -> {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (read == 0) {
                        int single = input.read(); if (single < 0) break; output.write(single);
                    } else output.write(buffer, 0, read);
                }
            });
        }
        @Override public BackupEntry writeGenerated(String path, ComponentSink.EntryWriter producer) throws IOException {
            boolean entered = false;
            try {
                check();
                if (entryOpen) throw new IOException("Capture cannot open nested entries");
                entryOpen = true; entered = true;
                Objects.requireNonNull(producer);
                if (path.startsWith("protected/") && secrets != SecretsSelection.INCLUDE_ENCRYPTED)
                    throw new IOException("Protected payload is outside the authorized selection");
                if (!paths.add(BackupPaths.collisionKey(path))) throw new IllegalArgumentException("Duplicate or colliding capture path");
                if (entries.size() >= limits.maxEntries()) throw new IOException("Capture entry limit exceeded");
                Path target = directory.resolve(path); Files.createDirectories(target.getParent());
                var digest = CapturedBackup.sha256(); EntryOutput entryOutput;
                try (var channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    try (var output = SpoolStreams.output(channel, protection, id, path)) {
                        entryOutput = new EntryOutput(output, digest);
                        try { producer.write(entryOutput); }
                        finally { entryOutput.invalidate(); }
                        check();
                    }
                    channel.force(true);
                }
                check();
                for (Path parent = target.getParent(); parent.startsWith(directory); parent = parent.getParent()) forceDirectory(parent);
                var entry = new BackupEntry(path, entryOutput.length, HexFormat.of().formatHex(digest.digest()));
                entries.add(entry); return entry;
            } catch (IOException | RuntimeException | Error failure) { poison(failure); throw failure; }
            finally { if (entered) entryOpen = false; }
        }
        private void poison(Throwable failure) {
            entryFailed = true;
            if (failure instanceof InterruptedIOException) Thread.currentThread().interrupt();
        }
        /** Callback-scoped view; only the sink owns encryption finalization and the durable channel. */
        private final class EntryOutput extends OutputStream {
            private final OutputStream delegate;
            private final java.security.MessageDigest digest;
            private long length;
            private volatile boolean closed;
            EntryOutput(OutputStream delegate, java.security.MessageDigest digest) { this.delegate = delegate; this.digest = digest; }
            private void active() throws IOException {
                requireCaptureThread();
                if (closed) throw new IOException("Generated entry stream is closed");
                progress();
            }
            @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
            @Override public void write(byte[] bytes) throws IOException {
                try { write(bytes, 0, bytes.length); }
                catch (RuntimeException | Error failure) { poison(failure); throw failure; }
            }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                try {
                    active(); Objects.checkFromIndexSize(offset, count, bytes.length);
                    if (count > limits.maxEntryBytes() - length || count > limits.maxTotalBytes() - total)
                        throw new IOException("Capture byte limit exceeded");
                    for (int position = offset, remaining = count; remaining > 0;) {
                        progress(); int chunk = Math.min(remaining, 64 * 1024);
                        delegate.write(bytes, position, chunk); digest.update(bytes, position, chunk);
                        length += chunk; total += chunk; position += chunk; remaining -= chunk;
                        progress();
                    }
                } catch (IOException | RuntimeException | Error failure) { poison(failure); throw failure; }
            }
            @Override public void flush() throws IOException {
                try { active(); delegate.flush(); progress(); }
                catch (IOException | RuntimeException | Error failure) { poison(failure); throw failure; }
            }
            @Override public void close() throws IOException {
                try {
                    requireCaptureThread();
                    if (!closed) { active(); delegate.flush(); progress(); }
                } catch (IOException | RuntimeException | Error failure) { poison(failure); throw failure; }
                finally { closed = true; }
            }
            void invalidate() { closed = true; }
        }
    }
}
