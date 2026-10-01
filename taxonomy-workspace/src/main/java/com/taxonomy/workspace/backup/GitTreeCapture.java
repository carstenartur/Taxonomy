package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import com.taxonomy.backup.BackupEntry;
import com.taxonomy.backup.BackupLimits;
import com.taxonomy.backup.ComponentSink;
import com.taxonomy.backup.PortableGitPaths;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.sha1.SHA1;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only evidence for one exact Git tree. Selection and stand-document projection are caller-owned. */
public final class GitTreeCapture {
    private static final int MAX_COMMIT_BYTES = 1_048_576;
    private static final int MAX_TREE_BYTES = 4 * 1_048_576;
    private static final int MAX_METADATA_BYTES = 16 * 1_048_576;
    private static final int BUFFER_BYTES = 8_192;
    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes) {
        public Limits {
            if (maxFiles < 1 || maxFiles > BackupLimits.MAX_ITEMS || maxFileBytes < 0 || maxTotalBytes < 0)
                throw new IllegalArgumentException("Invalid Git capture limits");
        }
    }
    public record File(String path, String objectId, int mode, long length, String sha256) { }

    private final Repository repository;
    private final String commit;
    private final String tree;
    private final List<File> files;
    private final Set<File> membership;
    private final BackupCheckpoint lifetime;

    private GitTreeCapture(Repository repository, String commit, String tree, List<File> files, BackupCheckpoint lifetime) {
        this.repository = repository; this.commit = commit; this.tree = tree;
        this.files = List.copyOf(files); membership = Set.copyOf(files); this.lifetime = lifetime;
    }

    /** The caller keeps the explicitly selected repository open and holds the capture fence. No refs are resolved or changed. */
    public static GitTreeCapture capture(Repository repository, String commit, Limits limits,
                                         BackupCheckpoint checkpoint) throws IOException {
        return capture(repository, commit, limits, checkpoint, () -> { });
    }
    static GitTreeCapture capture(Repository repository, String commit, Limits limits,
                                   BackupCheckpoint checkpoint, BackupCheckpoint lifetime) throws IOException {
        try {
            checkpoint = guarded(lifetime, checkpoint);
            Objects.requireNonNull(limits); checkpoint.check();
            if (commit == null || !commit.matches("[0-9a-f]{40}")) throw failure("Git capture requires a full commit ID");
            try (var reader = repository.newObjectReader()) {
                reader.setStreamFileThreshold(BUFFER_BYTES);
                var budget = new Budget(limits);
                byte[] raw = metadata(reader, ObjectId.fromString(commit), Constants.OBJ_COMMIT, MAX_COMMIT_BYTES, budget, checkpoint);
                var selected = RevCommit.parse(raw);
                var files = new ArrayList<File>();
                inspectTree(reader, selected.getTree(), "", files, budget, checkpoint);
                try { PortableGitPaths.requireTree(files.stream().map(File::path).toList()); }
                catch (IllegalArgumentException invalid) { throw failure("Git capture has colliding or non-portable paths"); }
                checkpoint.check();
                return new GitTreeCapture(repository, commit, selected.getTree().name(), files, lifetime);
            }
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    public String commit() { return commit; }
    public String tree() { return tree; }
    public List<File> files() { return files; }

    /** Copies raw captured bytes. Stand exporters must project embedded document history separately. Does not close output. */
    public void copy(File file, OutputStream output, BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint = guarded(lifetime, checkpoint);
            requireMember(file); Objects.requireNonNull(output); checkpoint.check();
            try (var reader = repository.newObjectReader()) {
                reader.setStreamFileThreshold(BUFFER_BYTES);
                var actual = blob(reader, ObjectId.fromString(file.objectId()), file.length(), output, checkpoint);
                if (!file.sha256().equals(actual)) throw failure("Captured Git content checksum changed");
            }
            checkpoint.check();
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    /** Uses the capture sink's protected streaming path and verifies both producer completion and its receipt. */
    public BackupEntry writeFile(File file, String path, ComponentSink sink) throws IOException {
        try {
            requireMember(file);
            var expected = new BackupEntry(path, file.length(), file.sha256());
            var owner = Thread.currentThread(); var invocations = new AtomicInteger(); boolean[] complete = {false};
            var receipt = sink.writeGenerated(path, output -> {
                if (invocations.incrementAndGet() != 1 || Thread.currentThread() != owner) throw failure("Invalid Git capture producer invocation");
                copy(file, output, sink::checkpoint); complete[0] = true;
            });
            if (invocations.get() != 1 || !complete[0] || !expected.equals(receipt)) throw failure("Git capture sink receipt mismatch");
            return receipt;
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    private void requireMember(File file) throws IOException {
        if (file == null || !membership.contains(file)) throw failure("File is outside the captured Git tree");
    }

    private static BackupCheckpoint guarded(BackupCheckpoint lifetime, BackupCheckpoint checkpoint) {
        return () -> { lifetime.check(); checkpoint.check(); lifetime.check(); };
    }

    private static void inspectTree(ObjectReader reader, ObjectId tree, String prefix, List<File> files,
                                    Budget budget, BackupCheckpoint checkpoint) throws IOException {
        var parser = new CanonicalTreeParser();
        parser.reset(metadata(reader, tree, Constants.OBJ_TREE, MAX_TREE_BYTES, budget, checkpoint));
        while (!parser.eof()) {
            checkpoint.check();
            if (++budget.entries > budget.limits.maxFiles() * 33) throw failure("Git tree metadata entry limit exceeded");
            String name = parser.getEntryPathString(), path = prefix + name;
            if (!Arrays.equals(Arrays.copyOf(parser.getEntryPathBuffer(), parser.getEntryPathLength()), name.getBytes(StandardCharsets.UTF_8)))
                throw failure("Git path is not canonical UTF-8");
            try { PortableGitPaths.requireFile(path); }
            catch (IllegalArgumentException invalid) { throw failure("Git capture has a non-portable path"); }
            int mode = parser.getEntryRawMode(); ObjectId id = parser.getEntryObjectId();
            if (mode == FileMode.TREE.getBits()) {
                inspectTree(reader, id, path + "/", files, budget, checkpoint);
            } else {
                if (mode != FileMode.REGULAR_FILE.getBits() && mode != FileMode.EXECUTABLE_FILE.getBits())
                    throw failure("Git capture requires regular files; symlinks and submodules are unsupported");
                if (files.size() >= budget.limits.maxFiles()) throw failure("Git capture file limit exceeded");
                long length = reader.getObjectSize(id, Constants.OBJ_BLOB);
                if (length < 0 || length > budget.limits.maxFileBytes() || length > budget.limits.maxTotalBytes() - budget.bytes)
                    throw failure("Git capture byte limit exceeded");
                String hash = blob(reader, id, length, OutputStream.nullOutputStream(), checkpoint);
                files.add(new File(path, id.name(), mode, length, hash)); budget.bytes += length;
            }
            parser.next();
        }
    }

    private static byte[] metadata(ObjectReader reader, ObjectId id, int type, int maximum,
                                    Budget budget, BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check(); long size = reader.getObjectSize(id, type);
        if (size < 0 || size > maximum || size > MAX_METADATA_BYTES - budget.metadata)
            throw failure("Git capture metadata byte limit exceeded");
        var loader = reader.open(id, type);
        if (loader.getSize() != size) throw failure("Git metadata size changed");
        byte[] bytes = loader.getBytes(maximum);
        if (bytes.length != size) throw failure("Git metadata size mismatch");
        var digest = gitDigest(type, size); digest.update(bytes);
        if (!digest.toObjectId().equals(id)) throw failure("Git metadata object checksum mismatch");
        new ObjectChecker().check(id, type, bytes);
        budget.metadata += bytes.length; checkpoint.check(); return bytes;
    }

    private static String blob(ObjectReader reader, ObjectId id, long length, OutputStream output,
                                BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check(); var loader = reader.open(id, Constants.OBJ_BLOB);
        if (loader.getSize() != length) throw failure("Captured Git blob size changed");
        var sha256 = sha256(); var git = gitDigest(Constants.OBJ_BLOB, length);
        byte[] buffer = new byte[BUFFER_BYTES], prefix = new byte[1_024]; int prefixLength = 0; long copied = 0;
        try (var input = loader.openStream()) {
            for (;;) {
                checkpoint.check(); int count = input.read(buffer);
                if (count == -1) break;
                if (count == 0) {
                    int single = input.read(); if (single < 0) break; buffer[0] = (byte) single; count = 1;
                }
                if (count > length - copied) throw failure("Git blob exceeds its declared size");
                int keep = Math.min(count, prefix.length - prefixLength);
                System.arraycopy(buffer, 0, prefix, prefixLength, keep); prefixLength += keep;
                sha256.update(buffer, 0, count); git.update(buffer, 0, count);
                output.write(buffer, 0, count); copied += count; checkpoint.check();
            }
        }
        if (copied != length) throw failure("Truncated captured Git blob");
        if (!git.toObjectId().equals(id)) throw failure("Git blob object checksum mismatch");
        String first = new String(prefix, 0, prefixLength, StandardCharsets.US_ASCII).split("\\r?\\n", 2)[0];
        if (first.equals("version https://git-lfs.github.com/spec/v1") || first.equals("version https://hawser.github.com/spec/v1"))
            throw failure("Git LFS contents are not retained; a pointer is not a standalone file");
        checkpoint.check(); return HexFormat.of().formatHex(sha256.digest());
    }

    private static SHA1 gitDigest(int type, long length) {
        var digest = SHA1.newInstance().setDetectCollision(true);
        digest.update((Constants.typeString(type) + " " + length + "\0").getBytes(StandardCharsets.US_ASCII)); return digest;
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static IOException failure(String message) { return new CaptureFailure(message); }
    /** Never propagate storage paths, credentials or suppressed provider cleanup diagnostics. */
    static IOException sanitized(Throwable problem) {
        var pending = new ArrayDeque<Throwable>(); pending.add(problem);
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean interrupted = Thread.currentThread().isInterrupted();
        while (!pending.isEmpty() && visited.size() < 100) {
            var next = pending.removeFirst(); if (!visited.add(next)) continue;
            interrupted |= next instanceof InterruptedIOException || next instanceof InterruptedException;
            if (next.getCause() != null) pending.add(next.getCause());
            Collections.addAll(pending, next.getSuppressed());
        }
        if (interrupted) {
            Thread.currentThread().interrupt(); return new InterruptedIOException("Git tree capture cancelled");
        }
        return new CaptureFailure(problem instanceof CaptureFailure ? problem.getMessage() : "Cannot read or copy the captured Git tree");
    }
    private static final class CaptureFailure extends IOException {
        CaptureFailure(String message) { super(message); }
    }
    private static final class Budget {
        final Limits limits; long bytes; int metadata; int entries;
        Budget(Limits limits) { this.limits = limits; }
    }
}
