package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.sha1.SHA1;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;

/** Counts before parsing and streams object verification; no complete blob is retained. */
final class GitHistoryObjects {
    record Proof(int type, long length, String sha256) {
        Proof(int type, long length) { this(type, length, null); }
        boolean sameHeader(Proof other) { return other != null && type == other.type && length == other.length; }
    }
    private static final int BUFFER = 8_192;
    private GitHistoryObjects() { }
    static ObjectReader inventoryReader(Repository repository, GitHistoryCapture.Limits limits, BackupCheckpoint check) throws IOException {
        return new Reader(repository.newObjectReader(), limits, null, check);
    }
    static ObjectReader capturedReader(Repository repository, GitHistoryCapture.Limits limits, Map<ObjectId, Proof> captured, BackupCheckpoint check) throws IOException {
        return new Reader(repository.newObjectReader(), limits, captured, check);
    }
    static Proof verify(ObjectReader reader, AnyObjectId id, BackupCheckpoint check) throws IOException {
        check.check(); var loader = reader.open(id); var proof = new Proof(loader.getType(), loader.getSize());
        if (proof.type() == Constants.OBJ_TREE) {
            var tree = new CanonicalTreeParser(); tree.reset(loader.getBytes(4 * 1_048_576));
            while (!tree.eof()) {
                check.check();
                if (tree.getEntryFileMode().equals(FileMode.GITLINK)) throw failure("Git submodule content needs an explicit self-contained backup adapter");
                tree.next();
            }
        }
        return new Proof(proof.type(), proof.length(), copy(loader, id, OutputStream.nullOutputStream(), check));
    }
    /** The receiver consumes a checked bounded stream; neither side materializes a large blob. */
    static void insertVerified(Repository source, Map<ObjectId, Proof> expected, Repository target,
                               BackupCheckpoint checkpoint) throws IOException {
        try (var reader = source.newObjectReader(); var insert = target.newObjectInserter()) {
            reader.setStreamFileThreshold(BUFFER);
            for (var id : expected.keySet().stream().sorted(Comparator.comparing(ObjectId::name)).toList()) {
                checkpoint.check(); var proof = expected.get(id); var loader = reader.open(id, proof.type());
                if (loader.getType() != proof.type() || loader.getSize() != proof.length() || proof.sha256() == null)
                    throw failure("Original Git object differs from its prepared header");
                try (var input = new VerifiedInput(loader.openStream(), id, proof, checkpoint)) {
                    if (!insert.insert(proof.type(), proof.length(), input).equals(id))
                        throw failure("Restored Git object identity differs from the original");
                    input.complete();
                }
            }
            checkpoint.check(); insert.flush(); checkpoint.check();
        }
    }
    private static final class VerifiedInput extends InputStream {
        private final InputStream source;
        private final ObjectId id;
        private final Proof proof;
        private final BackupCheckpoint checkpoint;
        private final SHA1 identity = SHA1.newInstance().setDetectCollision(true);
        private final MessageDigest content;
        private long count;
        VerifiedInput(InputStream source, ObjectId id, Proof proof, BackupCheckpoint checkpoint) {
            this.source = source; this.id = id; this.proof = proof; this.checkpoint = checkpoint;
            try { content = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            identity.update((Constants.typeString(proof.type()) + " " + proof.length() + "\0").getBytes(StandardCharsets.US_ASCII));
        }
        @Override public int read() throws IOException {
            var one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length); if (length == 0) return 0;
            checkpoint.check(); int read = source.read(bytes, offset, Math.min(length, BUFFER));
            if (read == 0) { int one = source.read(); if (one < 0) return -1; bytes[offset] = (byte) one; read = 1; }
            if (read < 0) return -1;
            if (read > proof.length() - count) throw failure("Original Git object exceeds its prepared length");
            identity.update(bytes, offset, read); content.update(bytes, offset, read); count += read; checkpoint.check(); return read;
        }
        void complete() throws IOException {
            if (count != proof.length() || read() != -1 || !identity.toObjectId().equals(id)
                    || !HexFormat.of().formatHex(content.digest()).equals(proof.sha256()))
                throw failure("Original Git object differs from its prepared content");
            checkpoint.check();
        }
        @Override public void close() throws IOException { source.close(); }
    }
    private static String copy(ObjectLoader loader, AnyObjectId id, OutputStream output, BackupCheckpoint check) throws IOException {
        check.check(); var sha = SHA1.newInstance().setDetectCollision(true);
        MessageDigest content;
        try { content = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        sha.update((Constants.typeString(loader.getType()) + " " + loader.getSize() + "\0").getBytes(StandardCharsets.US_ASCII));
        var buffer = new byte[BUFFER]; var prefix = new byte[1_024]; int prefixLength = 0; long count = 0;
        try (var input = loader.openStream()) {
            for (;;) {
                check.check(); int read = input.read(buffer); if (read < 0) break;
                if (read == 0) { int one = input.read(); if (one < 0) break; buffer[0] = (byte) one; read = 1; }
                if (read > loader.getSize() - count) throw failure("Git object length differs from captured evidence");
                int keep = Math.min(read, prefix.length - prefixLength);
                System.arraycopy(buffer, 0, prefix, prefixLength, keep); prefixLength += keep;
                output.write(buffer, 0, read); sha.update(buffer, 0, read); content.update(buffer, 0, read); count += read;
            }
        }
        if (count != loader.getSize() || !sha.toObjectId().equals(id)) throw failure("Git object identity differs from captured evidence");
        if (loader.getType() == Constants.OBJ_BLOB && new String(prefix, 0, prefixLength, StandardCharsets.US_ASCII)
                .split("\\r?\\n", 2)[0].equals("version https://git-lfs.github.com/spec/v1"))
            throw failure("Git LFS content needs an explicit self-contained backup adapter");
        check.check(); return HexFormat.of().formatHex(content.digest());
    }
    private static void requireContent(Proof expected, String actual) throws IOException {
        if (expected != null && expected.sha256() != null && !expected.sha256().equals(actual))
            throw failure("Git object content differs from captured evidence");
    }
    private static final class Reader extends ObjectReader {
        private final ObjectReader delegate;
        private final GitHistoryCapture.Limits limits;
        private final Map<ObjectId, Proof> captured;
        private final BackupCheckpoint check;
        private final Map<ObjectId, Proof> seen = new HashMap<>();
        private long bytes;
        private boolean active = true;
        Reader(ObjectReader delegate, GitHistoryCapture.Limits limits, Map<ObjectId, Proof> captured, BackupCheckpoint check) throws IOException {
            this.delegate = delegate; this.limits = limits; this.captured = captured; this.check = check;
            delegate.setStreamFileThreshold(BUFFER);
            if (!delegate.getShallowCommits().isEmpty()) { delegate.close(); throw failure("Shallow Git history is not a self-contained backup"); }
        }
        private void check() throws IOException { if (!active) throw failure("Git history reader is closed"); check.check(); if (!active) throw failure("Git history reader is closed"); }
        @Override public ObjectReader newReader() {
            try { check(); return new Reader(delegate.newReader(), limits, captured, this::check); }
            catch (IOException problem) { throw new java.io.UncheckedIOException(problem); }
        }
        @Override public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
            check();
            if (captured == null) throw failure("History capture requires full object identities");
            return captured.keySet().stream().filter(key -> id.prefixCompare(key) == 0).toList();
        }
        @Override public ObjectLoader open(AnyObjectId id, int type) throws IOException {
            check(); ObjectId objectId = id.copy();
            if (captured != null && !captured.containsKey(objectId)) throw failure("Git object is outside the captured history");
            var loader = delegate.open(objectId, type); var proof = new Proof(loader.getType(), loader.getSize());
            var expected = captured == null ? null : captured.get(objectId);
            if (proof.type() < Constants.OBJ_COMMIT || proof.type() > Constants.OBJ_TAG)
                throw failure("Git history has an unsupported object type");
            if (proof.length() < 0 || proof.length() > limits.maxObjectBytes()
                    || proof.type() == Constants.OBJ_TREE && proof.length() > 4 * 1_048_576
                    || (proof.type() == Constants.OBJ_COMMIT || proof.type() == Constants.OBJ_TAG) && proof.length() > 1_048_576)
                throw failure("Git history object byte limit exceeded");
            if (captured != null && !proof.sameHeader(expected)) throw failure("Git object is outside the captured history");
            if (!seen.containsKey(objectId)) {
                if (seen.size() >= limits.maxObjects() || proof.length() > limits.maxTotalBytes() - bytes) throw failure("Git history object closure limit exceeded");
                seen.put(objectId, proof); bytes += proof.length();
            }
            // Verify bounded metadata before RevWalk/PackWriter can follow any of its references.
            if (proof.type() != Constants.OBJ_BLOB) {
                var output = new ByteArrayOutputStream((int) proof.length());
                requireContent(expected, copy(loader, objectId, output, this::check));
                byte[] content = output.toByteArray(); new ObjectChecker().check(proof.type(), content);
                return new ObjectLoader.SmallObject(proof.type(), content);
            }
            if (captured == null) return loader;
            return new ObjectLoader() {
                @Override public int getType() { return proof.type(); }
                @Override public long getSize() { return proof.length(); }
                @Override public boolean isLarge() { return true; }
                @Override public byte[] getCachedBytes() { throw new LargeObjectException(); }
                @Override public ObjectStream openStream() throws IOException { throw failure("Captured Git blobs require synchronous streaming"); }
                @Override public void copyTo(OutputStream output) throws IOException {
                    Reader.this.check(); requireContent(expected, copy(loader, objectId, output, Reader.this::check));
                }
            };
        }
        @Override public Set<ObjectId> getShallowCommits() throws IOException { check(); return Set.of(); }
        @Override public void close() { if (active) { active = false; delegate.close(); } }
    }
}
