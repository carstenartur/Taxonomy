package com.taxonomy.backup.archive;

import com.taxonomy.backup.*;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.*;

/** Fail-closed ZIP64 import boundary. Never extracts files or instantiates package-supplied classes. */
public final class BackupArchiveReader {
    private final ArchiveProtectionProvider protection;
    private final ArchiveLimits limits;
    private final String applicationVersion;
    private final Map<BackupComponentId, Integer> components;
    public BackupArchiveReader(ArchiveProtectionProvider protection, ArchiveLimits limits, String applicationVersion,
                               Map<BackupComponentId, Integer> components) {
        this.protection = Objects.requireNonNull(protection); this.limits = Objects.requireNonNull(limits);
        this.applicationVersion = Objects.requireNonNull(applicationVersion); this.components = Map.copyOf(components);
    }
    public VerifiedBackup verify(Path path) throws IOException { return verify(path, ArchiveProgress.NONE); }
    public VerifiedBackup verify(Path path, ArchiveProgress progress) throws IOException {
        Path file = path.toAbsolutePath().normalize(); var guard = new ArchiveIO.Guard(limits, progress); guard.check();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)) throw new IOException("Invalid archive file");
        if (Files.size(file) > limits.maxArchiveBytes()) throw new IOException("Archive byte limit exceeded");
        var source = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel channel = source; ZipFile zip = null;
        try {
            // Hash and verify the same open file, even if the upload pathname is replaced concurrently.
            String digest = ArchiveIO.digest(source, limits.maxArchiveBytes(), guard);
            channel = protection.open(source, ArchiveIO.CONTEXT);
            var directory = ZipDirectoryBounds.inspect(channel, limits, guard);
            zip = ZipFile.builder().setSeekableByteChannel(channel).setUseUnicodeExtraFields(false).setMaxNumberOfDisks(1).get();
            var entries = inspectEntries(zip, channel, directory, guard);
            var manifestEntry = entries.remove("manifest.json");
            if (manifestEntry == null || manifestEntry.getSize() > BackupManifestCodec.MAX_MANIFEST_BYTES) throw new IOException("Manifest missing or size limit exceeded");
            BackupManifest manifest;
            try (var input = new VerifiedBackup.CheckedInput(zip.getInputStream(manifestEntry), manifestEntry.getSize(), null, manifestEntry.getCrc(), guard)) {
                manifest = new BackupManifestCodec().read(input.readNBytes(BackupManifestCodec.MAX_MANIFEST_BYTES + 1));
            }
            manifest.requireCompatible(applicationVersion, components);
            if (manifest.request().secrets() == SecretsSelection.INCLUDE_ENCRYPTED && !protection.encrypted())
                throw new IOException("Secret-bearing archives require authenticated encryption");
            if ((!protection.encrypted() || manifest.request().secrets() != SecretsSelection.INCLUDE_ENCRYPTED)
                    && entries.keySet().stream().anyMatch(p -> p.startsWith("protected/")))
                throw new IOException("Protected payload requires authenticated encryption");
            if (manifest.entries().size() != entries.size()) throw new IOException("Missing or undeclared archive entries");
            for (var expected : manifest.entries()) {
                var actual = entries.remove(expected.path());
                if (actual == null || actual.getSize() != expected.length()) throw new IOException("Missing entry or manifest length mismatch");
                try (var input = new VerifiedBackup.CheckedInput(zip.getInputStream(actual), expected.length(), expected.sha256(), actual.getCrc(), guard)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
            }
            if (!entries.isEmpty()) throw new IOException("Undeclared archive entries");
            if (!digest.equals(ArchiveIO.digest(source, limits.maxArchiveBytes(), guard)))
                throw new IOException("Archive changed during verification");
            guard.check(); return new VerifiedBackup(digest, manifest, zip, guard);
        } catch (IOException | RuntimeException failure) {
            try { if (zip == null) channel.close(); else zip.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof IOException io) throw io;
            // Package fields, crypto provider details and malformed JSON must never enter a public error.
            throw new IOException("Invalid or unsupported backup archive");
        }
    }
    private Map<String, ZipArchiveEntry> inspectEntries(ZipFile zip, SeekableByteChannel channel,
                                                       ZipDirectoryBounds.Directory directory, ArchiveIO.Guard guard) throws IOException {
        var entries = new LinkedHashMap<String, ZipArchiveEntry>(); var paths = new HashSet<String>();
        long expanded = 0, expectedOffset = 0;
        var enumeration = zip.getEntriesInPhysicalOrder();
        while (enumeration.hasMoreElements()) {
            guard.check(); var entry = enumeration.nextElement(); String path = entry.getName();
            if (entries.size() >= limits.maxEntries() + 1) throw new IOException("Archive entry count limit exceeded");
            String canonical = path.equals("manifest.json") ? path : BackupPaths.collisionKey(path);
            if (!paths.add(canonical) || entries.put(path, entry) != null) throw new IOException("Duplicate or colliding archive path");
            long size = entry.getSize(), compressed = entry.getCompressedSize();
            long entryLimit = path.equals("manifest.json") ? BackupManifestCodec.MAX_MANIFEST_BYTES : limits.maxEntryBytes();
            if (size < 0 || compressed < 0 || size > entryLimit || size > limits.maxExpandedBytes() - expanded
                    || size / (double) Math.max(1, compressed) > limits.maxCompressionRatio()) throw new IOException("Archive expansion or compression limit exceeded");
            expanded += size;
            int kind = entry.getUnixMode() & 0170000;
            if (entry.isDirectory() || entry.isUnixSymlink() || kind != 0 && kind != 0100000 || entry.getDiskNumberStart() != 0
                    || entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)
                    || entry.getMethod() != ZipArchiveEntry.STORED && entry.getMethod() != ZipArchiveEntry.DEFLATED
                    || entry.getUnparseableExtraFieldData() != null || entry.getComment() != null && !entry.getComment().isEmpty())
                throw new IOException("Unsupported archive entry type");
            for (var extra : entry.getExtraFields()) if (!extra.getHeaderId().equals(new ZipShort(0x0001)))
                throw new IOException("Unsupported archive entry metadata");
            if (entry.getLocalHeaderOffset() != expectedOffset) throw new IOException("Overlapping or hidden archive payload");
            var local = ZipDirectoryBounds.read(channel, expectedOffset, 30);
            if (local.getInt() != 0x04034b50) throw ZipDirectoryBounds.invalid();
            local.position(26); int nameLength = ZipDirectoryBounds.u16(local), extraLength = ZipDirectoryBounds.u16(local);
            if (nameLength > 2048 || extraLength > 128 || entry.getDataOffset() != expectedOffset + 30 + nameLength + extraLength)
                throw ZipDirectoryBounds.invalid();
            byte[] name = ZipDirectoryBounds.read(channel, expectedOffset + 30, nameLength).array();
            if (!Arrays.equals(name, entry.getRawName())) throw new IOException("Local and central archive paths differ");
            if (compressed > directory.offset() - entry.getDataOffset()) throw ZipDirectoryBounds.invalid();
            expectedOffset = entry.getDataOffset() + compressed;
            if (entry.getGeneralPurposeBit().usesDataDescriptor()) {
                var descriptor = ZipDirectoryBounds.read(channel, expectedOffset, 4);
                boolean signature = descriptor.getInt() == 0x08074b50;
                boolean zip64 = entry.getExtraField(new ZipShort(0x0001)) != null;
                var values = ZipDirectoryBounds.read(channel, expectedOffset + (signature ? 4 : 0), zip64 ? 20 : 12);
                long crc = ZipDirectoryBounds.u32(values), packed = zip64 ? values.getLong() : ZipDirectoryBounds.u32(values), unpacked = zip64 ? values.getLong() : ZipDirectoryBounds.u32(values);
                if (crc != entry.getCrc() || packed != compressed || unpacked != size) throw ZipDirectoryBounds.invalid();
                expectedOffset += (signature ? 4 : 0) + (zip64 ? 20 : 12);
            }
        }
        if (expectedOffset != directory.offset() || entries.size() != directory.entries()) throw ZipDirectoryBounds.invalid();
        return entries;
    }
}
