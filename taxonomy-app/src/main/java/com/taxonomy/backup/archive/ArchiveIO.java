package com.taxonomy.backup.archive;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

final class ArchiveIO {
    static final byte[] CONTEXT = "taxonomy.taxbackup.archive.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private ArchiveIO() { }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String digest(java.nio.channels.SeekableByteChannel input, long limit, Guard guard) throws IOException {
        var digest = sha256(); var buffer = java.nio.ByteBuffer.allocate(65536); long count = 0;
        long position = input.position(); input.position(0);
        try {
            for (int n; (n = input.read(buffer)) != -1;) {
                if (n > limit - count) throw new IOException("Archive byte limit exceeded");
                count += n; guard.advance(n); buffer.flip(); digest.update(buffer); buffer.clear();
            }
            return HexFormat.of().formatHex(digest.digest());
        } finally { input.position(position); }
    }
    static void forceDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix"))
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    static final class Guard {
        private final long deadline;
        private final ArchiveProgress progress;
        private long bytes;
        Guard(ArchiveLimits limits, ArchiveProgress progress) { this.deadline = System.nanoTime() + limits.maxDuration().toNanos(); this.progress = progress; }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup processing cancelled");
            if (System.nanoTime() - deadline >= 0) throw new IOException("Archive runtime limit exceeded");
            try { progress.check(bytes); }
            catch (InterruptedIOException cancelled) {
                Thread.currentThread().interrupt();
                throw cancelled;
            }
        }
        void advance(long count) throws IOException { bytes = Math.addExact(bytes, count); check(); }
    }
}
