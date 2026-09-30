package com.taxonomy.backup.snapshot;

import com.taxonomy.backup.BackupId;
import com.taxonomy.backup.archive.ArchiveProtectionProvider;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** Per-entry domain separation prevents swapping encrypted files between paths or captures. */
final class SpoolStreams {
    private SpoolStreams() { }
    static byte[] context(BackupId id, String path) {
        return ("taxonomy.taxbackup.spool.v1/" + id.value() + "/" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static OutputStream output(FileChannel channel, ArchiveProtectionProvider protection, BackupId id, String path) throws IOException {
        var raw = new FilterOutputStream(Channels.newOutputStream(channel)) {
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
            @Override public void close() throws IOException { flush(); }
        };
        return protection.protect(raw, context(id, path));
    }
    static InputStream input(Path file, ArchiveProtectionProvider protection, BackupId id, String path) throws IOException {
        var raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        try { return protection.unprotect(raw, context(id, path)); }
        catch (IOException | RuntimeException failure) { raw.close(); throw failure; }
    }
}
