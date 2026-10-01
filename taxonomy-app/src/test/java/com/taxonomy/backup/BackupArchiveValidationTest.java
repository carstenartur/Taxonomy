package com.taxonomy.backup;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.snapshot.*;
import org.apache.commons.compress.archivers.zip.*;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.CRC32;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class BackupArchiveValidationTest {
    @TempDir Path root;
    static final String DATA = "data/records/current.ndjson";
    static final BackupComponentId RECORDS = new BackupComponentId("records");
    static final Map<BackupComponentId, Integer> COMPONENTS = Map.of(RECORDS, 1, new BackupComponentId("capture-proof"), 1);
    static final String VERSION = "1.4.0-SNAPSHOT";
    final ArchiveProtectionProvider plain = ArchiveProtectionProvider.unprotected();

    @Test void zip64RoundTripSurvivesSourceDeletionAndRetainsManifest() throws Exception {
        Path archive = root.resolve("current.taxbackup"); BackupManifest expected;
        try (var capture = capture(plain, false, "saved current work\n")) {
            expected = capture.manifest();
            var published = new BackupArchiveWriter(plain, ArchiveLimits.defaults()).write(capture, archive);
            assertThat(published.path()).isEqualTo(archive);
            assertThat(published.length()).isEqualTo(Files.size(archive));
            assertThat(published.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))));
            assertThat(published.encrypted()).isFalse();
        }
        try (var verified = reader(plain).verify(archive)) {
            assertThat(verified.manifest()).isEqualTo(expected);
            try (var input = verified.openEntry(DATA)) { assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("saved current work\n"); }
            assertThatThrownBy(() -> verified.openEntry("data/foreign/private.ndjson")).isInstanceOf(IOException.class);
        }
        try (var zip = ZipFile.builder().setPath(archive).get()) {
            assertThat(zip.getEntry(DATA).getExtraField(new ZipShort(0x0001))).isNotNull();
        }
    }

    @Test void rejectsUnsafeCollidingSymlinkAndUndeclaredEntries() throws Exception {
        try (var capture = capture(plain, false, "current")) {
            var files = contents(capture);
            for (String path : List.of("../escape", "files/CON", "DATA/records/current.ndjson", DATA, "files/undeclared")) {
                var entries = new ArrayList<>(files); entries.add(new Entry(path, "hidden".getBytes(UTF_8), 0100600, false));
                assertThatThrownBy(() -> reader(plain).verify(zip(entries))).as(path).isInstanceOf(IOException.class);
            }
            var symbolic = new ArrayList<>(files); symbolic.set(0, new Entry(DATA, "current".getBytes(UTF_8), 0120777, false));
            assertThatThrownBy(() -> reader(plain).verify(zip(symbolic))).isInstanceOf(IOException.class);
            for (String path : List.of("files/caf\u00e9", "files/cafe\u0301")) files.add(new Entry(path, new byte[0], 0100600, false));
            assertThatThrownBy(() -> reader(plain).verify(zip(files))).isInstanceOf(IOException.class);
        }
    }

    @Test void rejectsMissingTamperedAndUnsupportedPayloads() throws Exception {
        try (var capture = capture(plain, false, "current")) {
            var files = contents(capture);
            var missing = files.stream().filter(e -> !e.path.equals(DATA)).toList();
            assertThatThrownBy(() -> reader(plain).verify(zip(missing))).isInstanceOf(IOException.class);
            var tampered = new ArrayList<>(files); tampered.set(0, new Entry(DATA, "changed".getBytes(UTF_8), 0100600, false));
            assertThatThrownBy(() -> reader(plain).verify(zip(tampered))).isInstanceOf(IOException.class).hasMessageContaining("digest");
            Path valid = zip(files);
            assertThatThrownBy(() -> new BackupArchiveReader(plain, ArchiveLimits.defaults(), VERSION, Map.of()).verify(valid))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> new BackupArchiveReader(plain, ArchiveLimits.defaults(), "older-version", COMPONENTS).verify(valid))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test void enforcesArchiveExpansionEntryCountAndCompressionLimitsBeforeImport() throws Exception {
        try (var capture = capture(plain, false, "current")) {
            var files = contents(capture); Path valid = zip(files);
            var tiny = new ArchiveLimits(100, 100, 100, 1, 100, Duration.ofMinutes(1));
            assertThatThrownBy(() -> new BackupArchiveReader(plain, tiny, VERSION, COMPONENTS).verify(valid)).isInstanceOf(IOException.class).hasMessageContaining("limit");
            var bomb = new ArrayList<>(files); bomb.set(0, new Entry(DATA, new byte[4 * 1024 * 1024], 0100600, true));
            assertThatThrownBy(() -> reader(plain).verify(zip(bomb))).isInstanceOf(IOException.class).hasMessageContaining("limit");
            var bytes = Files.readAllBytes(valid);
            // Lie about the central-directory count: reject before a ZIP library allocates its index.
            int end = bytes.length - 22; bytes[end + 10] = (byte) 255; bytes[end + 11] = (byte) 255;
            Path forged = root.resolve("forged.taxbackup"); Files.write(forged, bytes);
            assertThatThrownBy(() -> reader(plain).verify(forged)).isInstanceOf(IOException.class);
        }
    }

    @Test void cancellationAndDamagedCaptureNeverPublishACompleteArchive() throws Exception {
        try (var capture = capture(plain, false, "current")) {
            Path target = root.resolve("cancelled.taxbackup");
            try {
                assertThatThrownBy(() -> new BackupArchiveWriter(plain, ArchiveLimits.defaults()).write(capture, target,
                        bytes -> { throw new InterruptedIOException("cancelled"); })).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(target).doesNotExist();
            Files.writeString(capture.directory().resolve(DATA), "changed");
            assertThatThrownBy(() -> new BackupArchiveWriter(plain, ArchiveLimits.defaults()).write(capture, target)).isInstanceOf(IOException.class);
            assertThat(target).doesNotExist();
        }
        try (var paths = Files.list(root)) { assertThat(paths.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".partial")); }
    }

    @Test void secretsStayEncryptedInSpoolAndArchiveAndWrongKeysOrCorruptionFailClosed() throws Exception {
        var protection = encryption(); String secret = "PASSWORD-HASH-ONLY-IN-ENCRYPTED-BACKUP-" + UUID.randomUUID();
        Path archive = root.resolve("dr.taxbackup");
        try (var capture = capture(protection, true, secret)) {
            try (var paths = Files.walk(capture.directory())) {
                for (Path file : paths.filter(Files::isRegularFile).toList())
                    assertThat(new String(Files.readAllBytes(file), UTF_8)).doesNotContain(secret, "INCLUDE_ENCRYPTED");
            }
            try (var recovered = CapturedBackup.open(capture.directory(), capture.snapshot().authorization(), protection);
                 var input = recovered.openEntry("protected/local-passwords.ndjson")) {
                assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo(secret);
                new BackupArchiveWriter(protection, ArchiveLimits.defaults()).write(recovered, archive);
            }
        }
        assertThat(new String(Files.readAllBytes(archive), UTF_8)).doesNotContain(secret, "INCLUDE_ENCRYPTED");
        try (var verified = reader(protection).verify(archive)) {
            assertThat(verified.manifest().request().secrets()).isEqualTo(SecretsSelection.INCLUDE_ENCRYPTED);
            try (var input = verified.openEntry("protected/local-passwords.ndjson")) { assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo(secret); }
        }
        var wrong = encryption();
        assertThatThrownBy(() -> reader(wrong).verify(archive)).isInstanceOf(IOException.class);
        var bytes = Files.readAllBytes(archive); bytes[bytes.length / 2] ^= 1; Files.write(archive, bytes);
        assertThatThrownBy(() -> reader(protection).verify(archive)).isInstanceOf(IOException.class);
        Files.write(archive, Arrays.copyOf(bytes, bytes.length - 16));
        assertThatThrownBy(() -> reader(protection).verify(archive)).isInstanceOf(IOException.class);
    }

    @Test void cancellationDoesNotDrainTheRemainingCapturedPayload() throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var readsAfterCancel = new java.util.concurrent.atomic.AtomicInteger();
        ArchiveProtectionProvider measured = new ArchiveProtectionProvider() {
            public boolean encrypted() { return false; }
            public OutputStream protect(OutputStream output, byte[] context) { return output; }
            public java.nio.channels.SeekableByteChannel open(java.nio.channels.SeekableByteChannel input, byte[] context) { return input; }
            public InputStream unprotect(InputStream input, byte[] context) {
                return new FilterInputStream(input) {
                    public int read(byte[] data, int offset, int length) throws IOException {
                        if (cancelled.get()) readsAfterCancel.incrementAndGet();
                        return in.read(data, offset, length);
                    }
                };
            }
        };
        try (var capture = capture(measured, false, "x".repeat(256 * 1024))) {
            Path target = root.resolve("interrupted.taxbackup");
            try {
                assertThatThrownBy(() -> new BackupArchiveWriter(measured, ArchiveLimits.defaults()).write(capture, target, bytes -> {
                    if (bytes > 0) { cancelled.set(true); throw new InterruptedIOException("cancelled"); }
                })).isInstanceOf(InterruptedIOException.class);
                assertThat(readsAfterCancel).as("cancel must not drain a multi-gigabyte capture").hasValue(0);
                assertThat(target).doesNotExist();
            } finally { Thread.interrupted(); }
        }
    }

    @Test void plaintextContainersCannotClaimToIncludeSecrets() throws Exception {
        try (var capture = capture(encryption(), true, "secret")) {
            assertThatThrownBy(() -> new BackupArchiveWriter(plain, ArchiveLimits.defaults()).write(capture, root.resolve("plain.taxbackup")))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> reader(plain).verify(zip(contents(capture)))).isInstanceOf(IOException.class);
        }
    }

    @Test void bindsVerifiedDigestToTheOpenedFileWhenTheUploadPathIsReplaced() throws Exception {
        Path first = root.resolve("first.taxbackup"), replacement = root.resolve("replacement.taxbackup");
        BackupManifest expected;
        try (var capture = capture(plain, false, "original")) {
            expected = capture.manifest(); new BackupArchiveWriter(plain, ArchiveLimits.defaults()).write(capture, first);
        }
        try (var capture = capture(plain, false, "replacement")) {
            new BackupArchiveWriter(plain, ArchiveLimits.defaults()).write(capture, replacement);
        }
        long length = Files.size(first); var switched = new java.util.concurrent.atomic.AtomicBoolean();
        try (var verified = reader(plain).verify(first, bytes -> {
            if (bytes >= length && switched.compareAndSet(false, true))
                Files.move(replacement, first, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        })) {
            assertThat(verified.manifest()).isEqualTo(expected);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void encryptedArchiveLargerThanTheChildHeapUsesBoundedStreams(boolean generated) throws Exception {
        Path log = root.resolve("streaming.log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path arguments = root.resolve("probe.args");
        Files.writeString(arguments, "-Xmx96m\n-cp\n" + javaArgument(System.getProperty("java.class.path"))
                + "\n" + getClass().getName() + "\n" + javaArgument(root.resolve("large").toString()) + "\n" + generated + "\n");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)).as("bounded streaming probe finished").isTrue();
            assertThat(child.exitValue()).as(Files.readString(log)).isZero();
            assertThat(Files.readString(log)).contains("STREAMED=201326592");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    private static String javaArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Isolated JVM gives a hard, reproducible heap ceiling without depending on GC timing. */
    public static void main(String[] args) throws Exception {
        var test = new BackupArchiveValidationTest(); test.root = Path.of(args[0]); Files.createDirectories(test.root);
        long length = 192L << 20; var protection = test.encryption(); Path archive = test.root.resolve("large.taxbackup");
        Source inputSource = () -> new InputStream() {
            long remaining = length;
            public int read() { if (remaining == 0) return -1; remaining--; return 42; }
            public int read(byte[] buffer, int offset, int requested) {
                if (remaining == 0) return -1;
                if (requested > 65536) throw new AssertionError("Unbounded source read");
                int count = (int) Math.min(remaining, requested); Arrays.fill(buffer, offset, offset + count, (byte) 42); remaining -= count; return count;
            }
        };
        boolean generated = args.length > 1 && Boolean.parseBoolean(args[1]);
        ComponentSink.EntryWriter producer = output -> {
            byte[] buffer = new byte[65536]; Arrays.fill(buffer, (byte) 42);
            for (long remaining = length; remaining > 0; remaining -= buffer.length) output.write(buffer, 0, (int) Math.min(remaining, buffer.length));
        };
        try (var capture = test.capture(protection, true, generated ? null : inputSource, generated ? producer : null)) {
            new BackupArchiveWriter(protection, ArchiveLimits.defaults()).write(capture, archive);
        }
        try (var verified = test.reader(protection).verify(archive); var input = verified.openEntry("protected/local-passwords.ndjson")) {
            if (input.transferTo(OutputStream.nullOutputStream()) != length) throw new AssertionError("Incomplete large export");
        }
        System.out.println("STREAMED=" + length);
    }

    private BackupArchiveReader reader(ArchiveProtectionProvider protection) {
        return new BackupArchiveReader(protection, ArchiveLimits.defaults(), VERSION, COMPONENTS);
    }
    private ArchiveProtectionProvider encryption() throws Exception {
        StreamingAeadConfig.register();
        return new TinkArchiveProtection(KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB));
    }
    private CapturedBackup capture(ArchiveProtectionProvider protection, boolean secrets, String text) throws Exception {
        return capture(protection, secrets, () -> new ByteArrayInputStream(text.getBytes(UTF_8)));
    }
    @FunctionalInterface private interface Source { InputStream open() throws IOException; }
    private CapturedBackup capture(ArchiveProtectionProvider protection, boolean secrets, Source source) throws Exception {
        return capture(protection, secrets, source, null);
    }
    private CapturedBackup capture(ArchiveProtectionProvider protection, boolean secrets, Source source, ComponentSink.EntryWriter producer) throws Exception {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:archive-" + UUID.randomUUID()); database.setUser("sa");
        BackupMaintenanceLease.initialize(database);
        var barrier = new BackupMaintenanceLease(database, Duration.ofSeconds(30));
        BackupAccessPolicy policy = new BackupAccessPolicy() {
            public boolean isEnabled(PrincipalId id) { return true; }
            public boolean hasCapability(PrincipalId id, BackupCapability capability, BackupScope scope) { return true; }
            public boolean canRead(PrincipalId id, BackupRepositoryKey repository) { return true; }
            public boolean canReadVersion(PrincipalId id, BackupRepositoryKey repository, String commit) { return true; }
        };
        var auth = new BackupAuthorizationService(policy, Clock.systemUTC());
        var request = secrets ? new BackupRequest(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation(), new BackupTime.History(), GitRepresentation.BUNDLE, SecretsSelection.INCLUDE_ENCRYPTED)
                : new BackupRequest(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo", "workspace"), new BackupTime.Current(), GitRepresentation.NONE, SecretsSelection.EXCLUDE);
        List<BackupManifest.Repository> repositories = secrets ? List.of() : List.of(new BackupManifest.Repository(new BackupRepositoryKey("repo", "workspace"), "opaque",
                GitRepresentation.NONE, new SnapshotContext.RepositoryState(Map.of(), null, Map.of(), Set.of()), null, null, null));
        var plan = new BackupSnapshotCoordinator.CapturePlan(VERSION, "test", "installation", repositories,
                Map.of(RECORDS, new BackupSnapshotCoordinator.CaptureComponent(1, BackupCompleteness.COMPLETE, Set.of())), List.of(), List.of());
        BackupContributor contributor = new BackupContributor() {
            public BackupComponentId componentId() { return RECORDS; }
            public int schemaVersion() { return 1; }
            public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
                String path = secrets ? "protected/local-passwords.ndjson" : DATA;
                if (producer != null) sink.writeGenerated(path, producer);
                else try (var input = source.open()) { sink.write(path, input); }
            }
        };
        return new BackupSnapshotCoordinator(barrier, auth, ignored -> plan, List.of(contributor), root.resolve("spool"),
                BackupSnapshotCoordinator.CaptureLimits.defaults(), Clock.systemUTC(), protection).capture(auth.authorize(PrincipalId.create(), request));
    }
    private List<Entry> contents(CapturedBackup capture) throws Exception {
        var files = new ArrayList<Entry>();
        for (var entry : capture.manifest().entries()) try (var input = capture.openEntry(entry.path())) {
            files.add(new Entry(entry.path(), input.readAllBytes(), 0100600, false));
        }
        files.add(new Entry("manifest.json", new BackupManifestCodec().write(capture.manifest()), 0100600, false)); return files;
    }
    private record Entry(String path, byte[] data, int mode, boolean compressed) { }
    private Path zip(List<Entry> files) throws Exception {
        Path archive = root.resolve(UUID.randomUUID() + ".taxbackup");
        try (var out = new ZipArchiveOutputStream(archive)) {
            out.setUseZip64(Zip64Mode.AsNeeded);
            for (var file : files) {
                var entry = new ZipArchiveEntry(file.path); entry.setUnixMode(file.mode); entry.setSize(file.data.length);
                entry.setMethod(file.compressed ? ZipArchiveEntry.DEFLATED : ZipArchiveEntry.STORED);
                var crc = new CRC32(); crc.update(file.data); entry.setCrc(crc.getValue());
                out.putArchiveEntry(entry); out.write(file.data); out.closeArchiveEntry();
            }
        }
        return archive;
    }
}
