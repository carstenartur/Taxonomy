package com.taxonomy.backup.archive;

import com.taxonomy.backup.runtime.BackupManifestCodec;

import com.taxonomy.backup.*;
import com.taxonomy.backup.snapshot.CapturedBackup;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.CRC32;

/** Streams from immutable capture, verifies the closed artifact, then atomically publishes it. */
public final class BackupArchiveWriter {
    private final ArchiveProtectionProvider protection;
    private final ArchiveLimits limits;
    private final com.taxonomy.backup.runtime.BackupFeaturePrerequisites prerequisites;
    public record PublishedArchive(Path path, long length, String sha256, boolean encrypted) { }
    public BackupArchiveWriter(ArchiveProtectionProvider protection, ArchiveLimits limits,
                               com.taxonomy.backup.runtime.BackupFeaturePrerequisites prerequisites) {
        this.prerequisites = Objects.requireNonNull(prerequisites);
        this.protection = Objects.requireNonNull(protection); this.limits = Objects.requireNonNull(limits);
    }
    public PublishedArchive write(CapturedBackup captured, Path target) throws IOException { return write(captured, target, ArchiveProgress.NONE); }
    public PublishedArchive write(CapturedBackup captured, Path target, ArchiveProgress progress) throws IOException {
        return write(captured, target, progress, () -> { });
    }
    @FunctionalInterface public interface VerificationStarting { void start() throws IOException; }
    public PublishedArchive write(CapturedBackup captured, Path target, ArchiveProgress progress, VerificationStarting verification) throws IOException {
        var manifest = captured.manifest(); var guard = new ArchiveIO.Guard(limits, progress); guard.check();
        if (manifest.request().secrets() == SecretsSelection.INCLUDE_ENCRYPTED && !protection.encrypted())
            throw new IOException("Secret-bearing archives require authenticated encryption");
        if (manifest.request().secrets() == SecretsSelection.EXCLUDE && manifest.entries().stream().anyMatch(e -> e.path().startsWith("protected/")))
            throw new IOException("Protected payload is outside the authorized selection");
        Path destination = target.toAbsolutePath().normalize(), parent = destination.getParent();
        Files.createDirectories(parent);
        if (!parent.toRealPath().equals(parent) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive destination is unavailable");
        Path temporary = Files.getFileStore(parent).supportsFileAttributeView("posix")
                ? Files.createTempFile(parent, ".archive-", ".partial", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(parent, ".archive-", ".partial");
        boolean moved = false;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var raw = new FilterOutputStream(Channels.newOutputStream(channel)) {
                    long bytes;
                    @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}, 0, 1); }
                    @Override public void write(byte[] data, int offset, int length) throws IOException {
                        guard.check(); if (length > limits.maxArchiveBytes() - bytes) throw new IOException("Archive byte limit exceeded");
                        out.write(data, offset, length); bytes += length;
                    }
                    @Override public void close() throws IOException { flush(); }
                };
                try (var protectedOutput = protection.protect(raw, ArchiveIO.CONTEXT);
                     var zip = new ZipArchiveOutputStream(protectedOutput)) {
                    zip.setUseZip64(Zip64Mode.AlwaysWithCompatibility); zip.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
                    // Stored ZIP entries keep output/CPU bounds predictable and avoid rejecting our own highly compressible payloads.
                    for (var entry : manifest.entries()) {
                        guard.check(); CRC32 crc = new CRC32();
                        try (var input = captured.openEntry(entry.path())) { consume(input, OutputStream.nullOutputStream(), crc, guard); }
                        var member = member(entry.path(), entry.length(), crc.getValue()); zip.putArchiveEntry(member);
                        try (var input = captured.openEntry(entry.path())) { consume(input, zip, null, guard); }
                        zip.closeArchiveEntry();
                    }
                    byte[] document = new BackupManifestCodec().write(manifest); var crc = new CRC32(); crc.update(document);
                    zip.putArchiveEntry(member("manifest.json", document.length, crc.getValue())); zip.write(document); zip.closeArchiveEntry();
                }
                channel.force(true);
            }
            var versions = manifest.components().stream().collect(Collectors.toMap(BackupManifest.Component::id, BackupManifest.Component::version));
            verification.start(); guard.report(0);
            String digest;
            try (var verified = new BackupArchiveReader(protection, limits, manifest.applicationVersion(), versions, prerequisites).verify(temporary, guard::report)) {
                if (!verified.manifest().equals(manifest)) throw new IOException("Archive manifest changed during writing");
                digest = verified.archiveSha256();
            }
            guard.check(); long length = Files.size(temporary);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); moved = true; ArchiveIO.forceDirectory(parent); guard.check();
            return new PublishedArchive(destination, length, digest, protection.encrypted());
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(moved ? destination : temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static ZipArchiveEntry member(String path, long size, long crc) {
        var entry = new ZipArchiveEntry(path); entry.setMethod(ZipArchiveEntry.STORED); entry.setSize(size); entry.setCrc(crc);
        entry.setUnixMode(0100600); entry.setTime(631152000000L); return entry;
    }
    private static void consume(InputStream input, OutputStream output, CRC32 crc, ArchiveIO.Guard guard) throws IOException {
        byte[] buffer = new byte[65536];
        for (int n; (n = input.read(buffer)) != -1;) { guard.advance(n); if (crc != null) crc.update(buffer, 0, n); output.write(buffer, 0, n); }
    }
}
