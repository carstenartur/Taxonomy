package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.ArchiveProtectionProvider;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/** Immutable capture metadata and verified streams from an application-private durable spool. */
public final class CapturedBackup implements AutoCloseable {
    static final String EVIDENCE_PREFIX = "writer-barrier-v1:installation:generation=";
    private final Path directory;
    private final BackupManifest manifest;
    private final SnapshotContext snapshot;
    private final Map<String, BackupEntry> entries;
    private final ArchiveProtectionProvider protection;

    private CapturedBackup(Path directory, BackupManifest manifest, AuthorizedBackupRequest authorization, ArchiveProtectionProvider protection) {
        this.protection = protection;
        this.directory = directory; this.manifest = manifest;
        String evidence = manifest.consistencyEvidence();
        if (!evidence.startsWith(EVIDENCE_PREFIX)) throw new IllegalArgumentException("Unsupported capture evidence");
        long generation = Long.parseLong(evidence.substring(EVIDENCE_PREFIX.length()));
        this.snapshot = new SnapshotContext(manifest.backupId(), authorization, manifest.captureStartedAt(), manifest.captureCompletedAt(), generation,
                manifest.repositories().stream().collect(Collectors.toMap(BackupManifest.Repository::id, BackupManifest.Repository::captured)),
                manifest.components().stream().collect(Collectors.toMap(BackupManifest.Component::id, BackupManifest.Component::version)));
        this.entries = manifest.entries().stream().collect(Collectors.toUnmodifiableMap(BackupEntry::path, e -> e));
    }

    /** Internal recovery API; the job boundary must reauthorize before using this handle. */
    public static CapturedBackup open(Path directory, AuthorizedBackupRequest authorization) throws IOException {
        return open(directory, authorization, ArchiveProtectionProvider.unprotected());
    }

    public static CapturedBackup open(Path directory, AuthorizedBackupRequest authorization, ArchiveProtectionProvider protection) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
            throw new IOException("Invalid capture directory");
        BackupId id;
        try { id = new BackupId(UUID.fromString(root.getFileName().toString().substring("capture-".length()))); }
        catch (RuntimeException failure) { throw new IOException("Invalid capture identifier"); }
        BackupManifest manifest;
        try (var input = SpoolStreams.input(root.resolve("manifest.json"), protection, id, "manifest.json")) {
            manifest = new BackupManifestCodec().read(input.readNBytes(BackupManifestCodec.MAX_MANIFEST_BYTES + 1));
        }
        if (!root.getFileName().toString().equals("capture-" + manifest.backupId().value())
                || !manifest.request().equals(authorization.request())) throw new IOException("Capture does not match the authorized job");
        Set<String> paths = new HashSet<>();
        for (var entry : manifest.entries()) {
            if (!paths.add(BackupPaths.collisionKey(entry.path()))) throw new IOException("Colliding capture path");
        }
        if (manifest.request().secrets() == SecretsSelection.INCLUDE_ENCRYPTED && !protection.encrypted())
            throw new IOException("Encrypted staging is required for secrets");
        var captured = new CapturedBackup(root, manifest, authorization, protection);
        // Never adopt extra files or symlinks left by a replaced/tampered staging directory.
        try (var files = Files.walk(root)) {
            var iterator = files.iterator(); int count = 0;
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (++count > 1 + manifest.entries().size() * 32 + 2) throw new IOException("Capture directory entry limit exceeded");
                if (Files.isSymbolicLink(file)) throw new IOException("Symlink in capture");
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (!relative.equals("manifest.json") && !captured.entries.containsKey(relative)) throw new IOException("Undeclared capture entry");
            }
        }
        for (var entry : manifest.entries()) captured.entryPath(entry);
        return captured;
    }

    public SnapshotContext snapshot() { return snapshot; }
    public BackupManifest manifest() { return manifest; }
    public Path directory() { return directory; }

    public InputStream openEntry(String path) throws IOException {
        BackupEntry entry = entries.get(BackupPaths.requireEntry(path));
        if (entry == null) throw new IOException("Entry is not part of the captured manifest");
        return new VerifiedInput(SpoolStreams.input(entryPath(entry), protection, manifest.backupId(), path), entry);
    }

    private Path entryPath(BackupEntry entry) throws IOException {
        Path file = directory.resolve(entry.path());
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)
                || !protection.encrypted() && Files.size(file) != entry.length()) throw new IOException("Capture entry length or path differs from its manifest");
        return file;
    }

    @Override public void close() throws IOException { delete(directory); }

    static void delete(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static final class VerifiedInput extends FilterInputStream {
        private final BackupEntry entry;
        private final MessageDigest digest = sha256();
        private long count;
        private boolean verified;
        VerifiedInput(InputStream input, BackupEntry entry) { super(input); this.entry = entry; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup capture read cancelled");
            int read = in.read(bytes, offset, length);
            if (read > 0) {
                count = Math.addExact(count, read);
                if (count > entry.length()) throw new IOException("Capture entry length changed");
                digest.update(bytes, offset, read);
            } else if (read < 0 && !verified) {
                if (count != entry.length() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))
                    throw new IOException("Capture entry digest mismatch");
                verified = true;
            }
            return read;
        }
        @Override public long skip(long count) throws IOException {
            long remaining = Math.max(0, count); byte[] buffer = new byte[8192];
            while (remaining > 0) { int read = read(buffer, 0, (int) Math.min(remaining, buffer.length)); if (read < 0) break; remaining -= read; }
            return Math.max(0, count) - remaining;
        }
        @Override public boolean markSupported() { return false; }
        @Override public synchronized void reset() throws IOException { throw new IOException("Captured streams cannot be rewound"); }
        @Override public void close() throws IOException {
            try { if (!verified) transferTo(OutputStream.nullOutputStream()); }
            finally { in.close(); }
        }
    }
}
