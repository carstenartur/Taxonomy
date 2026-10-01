package com.taxonomy.backup.jobs;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** A configured application-private volume, shared by all application instances that serve these jobs. */
public final class BackupJobStorage {
    private final Path root;
    public BackupJobStorage(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        createPrivateDirectory(this.root);
        requireDirectory(this.root);
    }
    public Path workDirectory(BackupJobClaim claim) throws IOException {
        Path path = directory(claim.artifactName()); createPrivateDirectory(path); requireDirectory(path); return path;
    }
    public Path archiveTarget(BackupJobClaim claim) throws IOException { return workDirectory(claim).resolve("archive.taxbackup"); }
    public Path artifactPath(BackupArtifact artifact) throws IOException {
        Path parent = directory(artifact.name()); requireDirectory(parent); return parent.resolve("archive.taxbackup");
    }
    public void discard(String name) throws IOException {
        if (name == null) return;
        Path path = directory(name);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        // walkFileTree does not follow symlinks, including a replaced root.
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure; Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
    public Download open(BackupArtifact receipt, String filename, Runnable accessCheck) throws IOException {
        Path path = artifactPath(receipt);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Backup artifact is unavailable");
        var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        boolean accepted = false;
        try {
            if (channel.size() != receipt.length()) throw new IOException("Backup artifact verification failed");
            var digest = sha256(); var buffer = ByteBuffer.allocate(65536); long count = 0, checked = 0;
            accessCheck.run();
            for (int n; (n = channel.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup download cancelled");
                if (n > receipt.length() - count) throw new IOException("Backup artifact verification failed");
                count += n; buffer.flip(); digest.update(buffer); buffer.clear();
                if (count - checked >= 4L << 20) { accessCheck.run(); checked = count; }
            }
            if (count != receipt.length() || !HexFormat.of().formatHex(digest.digest()).equals(receipt.sha256()))
                throw new IOException("Backup artifact verification failed");
            accessCheck.run(); channel.position(0); accepted = true;
            return new Download(channel, receipt, filename, accessCheck);
        } finally { if (!accepted) channel.close(); }
    }
    private Path directory(String name) throws IOException {
        // Apply the same strict generated-name contract on recovery paths as on artifact receipts.
        new BackupArtifact(name, 1, "0".repeat(64), false);
        requireDirectory(root); return root.resolve(name);
    }
    private static void requireDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
            throw new IOException("Backup storage is unavailable");
    }
    private static void createPrivateDirectory(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(path);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    /** Owns the same descriptor that was verified; no path is returned to the HTTP layer. */
    public static final class Download implements AutoCloseable {
        private final FileChannel channel;
        private final BackupArtifact receipt;
        private final String filename;
        private final Runnable accessCheck;
        private boolean consumed;
        Download(FileChannel channel, BackupArtifact receipt, String filename, Runnable accessCheck) {
            this.channel = channel; this.receipt = receipt; this.filename = filename; this.accessCheck = accessCheck;
        }
        public long length() { return receipt.length(); }
        public String filename() { return filename; }
        public void writeTo(OutputStream output) throws IOException {
            if (consumed) throw new IOException("Backup download was already consumed"); consumed = true;
            accessCheck.run(); var buffer = ByteBuffer.allocate(65536); var digest = sha256(); long count = 0, checked = 0, checkedAt = System.nanoTime();
            for (int n; (n = channel.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Backup download cancelled");
                if (n > receipt.length() - count) throw new IOException("Backup artifact changed");
                count += n;
                if (count - checked >= 4L << 20 || System.nanoTime() - checkedAt >= 250_000_000) {
                    accessCheck.run(); checked = count; checkedAt = System.nanoTime();
                }
                digest.update(buffer.array(), 0, n); output.write(buffer.array(), 0, n); buffer.clear();
            }
            accessCheck.run();
            if (count != receipt.length() || !HexFormat.of().formatHex(digest.digest()).equals(receipt.sha256())) throw new IOException("Backup artifact changed");
        }
        @Override public void close() throws IOException { channel.close(); }
    }
}
