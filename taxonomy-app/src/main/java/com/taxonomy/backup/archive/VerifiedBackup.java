package com.taxonomy.backup.archive;

import com.taxonomy.backup.*;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/** Only the complete reader verification can construct this handle; close releases the archive file. */
public final class VerifiedBackup implements AutoCloseable {
    private final String digest;
    private final BackupManifest manifest;
    private final ZipFile zip;
    private final Map<String, BackupEntry> entries;
    private final ArchiveIO.ReadGuard guard;

    VerifiedBackup(String digest, BackupManifest manifest, ZipFile zip, ArchiveIO.ReadGuard guard) {
        this.digest = digest; this.manifest = manifest; this.zip = zip; this.guard = guard;
        entries = new HashMap<>(); manifest.entries().forEach(entry -> entries.put(entry.path(), entry));
    }
    public BackupManifest manifest() { return manifest; }
    public String archiveSha256() { return digest; }
    public InputStream openEntry(String path) throws IOException {
        var entry = entries.get(path);
        if (entry == null) throw new IOException("Entry is not declared by the verified manifest");
        var archived = zip.getEntry(path);
        return new CheckedInput(zip.getInputStream(archived), entry.length(), entry.sha256(), archived.getCrc(), guard);
    }
    @Override public void close() throws IOException { zip.close(); }

    static final class CheckedInput extends FilterInputStream {
        private final long length, expectedCrc;
        private final String expectedDigest;
        private final MessageDigest digest = ArchiveIO.sha256();
        private final CRC32 crc = new CRC32();
        private final ArchiveIO.ReadGuard guard;
        private long count;
        private boolean complete, failed;
        CheckedInput(InputStream stream, long length, String digest, long crc, ArchiveIO.ReadGuard guard) {
            super(stream); this.length = length; expectedDigest = digest; expectedCrc = crc; this.guard = guard;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }
        @Override public int read(byte[] bytes, int offset, int size) throws IOException {
            try {
                guard.check(); int n = in.read(bytes, offset, size);
                if (n > 0) {
                    if (n > length - count) throw new IOException("Archive entry length limit exceeded");
                    count += n; digest.update(bytes, offset, n); crc.update(bytes, offset, n); guard.check();
                } else if (n < 0 && !complete) {
                    if (count != length || crc.getValue() != expectedCrc
                            || expectedDigest != null && !HexFormat.of().formatHex(digest.digest()).equals(expectedDigest))
                        throw new IOException("Archive entry digest or length mismatch");
                    complete = true;
                }
                return n;
            } catch (IOException failure) { failed = true; throw failure; }
        }
        @Override public long skip(long amount) throws IOException {
            long remaining = Math.max(0, amount); byte[] bytes = new byte[8192];
            while (remaining > 0) { int n = read(bytes, 0, (int) Math.min(bytes.length, remaining)); if (n < 0) break; remaining -= n; }
            return Math.max(0, amount) - remaining;
        }
        @Override public boolean markSupported() { return false; }
        @Override public void reset() throws IOException { throw new IOException("Verified streams cannot be rewound"); }
        void abort() { failed = true; }
        @Override public void close() throws IOException {
            try { if (!failed && !complete) transferTo(OutputStream.nullOutputStream()); } finally { in.close(); }
        }
    }
}
