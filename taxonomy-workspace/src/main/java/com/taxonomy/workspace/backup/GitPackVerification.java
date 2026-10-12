package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.PackedObjectInfo;
import java.io.*;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Read-only validation of the bounded, non-delta pack format emitted by portable history export. */
public final class GitPackVerification {
    private static final int BUFFER_BYTES = 8_192;
    private static final long MAX_COMMIT_OR_TAG_BYTES = 1_048_576;
    private static final long MAX_TREE_BYTES = 4 * 1_048_576;

    private GitPackVerification() { }
    public record Result(int objectCount,long expandedBytes,Set<String> objectIds,String packChecksum) {
        public Result {objectIds=Set.copyOf(objectIds);}
    }
    /**
     * Validates the complete stream without creating objects, refs, or pack files. The caller supplies
     * a fresh private repository as the parser context and retains ownership of both input and context.
     * The public JGit parser verifies object syntax, identities, inflation, checksum, and exact EOF.
     */
    public static Result inspect(Repository context, InputStream input, int expectedObjects,
            GitHistoryCapture.Limits limits, BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(context); Objects.requireNonNull(input);
            Objects.requireNonNull(limits); Objects.requireNonNull(checkpoint);
            if (expectedObjects < 0 || expectedObjects > limits.maxObjects())
                throw failure("Git pack object count exceeds the capture limit");
            BackupCheckpoint check = () -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Git pack verification cancelled");
                checkpoint.check();
            };
            check.check();
            var parser = new Validator(context, checked(input, check), expectedObjects, limits, check);
            parser.parse(NullProgressMonitor.INSTANCE);
            check.check();
            if (parser.getObjectCount() != expectedObjects || parser.objectIds.size() != expectedObjects
                    || parser.packChecksum == null)
                throw failure("Git pack object proof is incomplete");
            return new Result(expectedObjects, parser.expandedBytes, parser.objectIds, parser.packChecksum);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    private static InputStream checked(InputStream input, BackupCheckpoint check) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException {
                byte[] value = new byte[1];
                return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length); check.check();
                if (length == 0) return 0;
                int count = in.read(bytes, offset, Math.min(length, BUFFER_BYTES));
                check.check(); return count;
            }
        };
    }

    /** Only the public parser extension points are used; storage callbacks deliberately never write. */
    private static final class Validator extends PackParser {
        private final int expectedObjects;
        private final GitHistoryCapture.Limits limits;
        private final BackupCheckpoint check;
        private final Set<String> objectIds = new HashSet<>();
        private long expandedBytes;
        private String packChecksum;

        Validator(Repository context, InputStream input, int expectedObjects,
                GitHistoryCapture.Limits limits, BackupCheckpoint check) {
            super(context.getObjectDatabase(), input);
            this.expectedObjects = expectedObjects; this.limits = limits; this.check = check;
            setAllowThin(false); setCheckEofAfterPackFooter(true); setObjectChecking(true);
        }

        @Override protected void onPackHeader(long objectCount) throws IOException {
            check.check();
            // JGit invokes this before allocating its object-info array.
            if (objectCount != expectedObjects || objectCount < 0 || objectCount > limits.maxObjects())
                throw failure("Git pack object count does not match the bounded capture");
        }
        @Override protected void onBeginWholeObject(long offset, int type, long length) throws IOException {
            check.check();
            long metadataLimit = switch (type) {
                case Constants.OBJ_COMMIT, Constants.OBJ_TAG -> MAX_COMMIT_OR_TAG_BYTES;
                case Constants.OBJ_TREE -> MAX_TREE_BYTES;
                case Constants.OBJ_BLOB -> limits.maxObjectBytes();
                default -> throw failure("Git pack contains an unsupported object type");
            };
            // This callback precedes inflation and JGit's commit/tree/tag byte-array allocation.
            if (length < 0 || length > metadataLimit || length > limits.maxObjectBytes()
                    || length > limits.maxTotalBytes() - expandedBytes)
                throw failure("Git pack expanded object byte limit exceeded");
            expandedBytes += length;
        }
        @Override protected void onEndWholeObject(PackedObjectInfo info) throws IOException {
            check.check();
            if (objectIds.size() >= expectedObjects || !objectIds.add(info.name()))
                throw failure("Git pack contains duplicate or excess objects");
        }
        @Override protected void onPackFooter(byte[] checksum) throws IOException {
            check.check();
            if (checksum.length != Constants.OBJECT_ID_LENGTH) throw failure("Invalid Git pack checksum");
            packChecksum = HexFormat.of().formatHex(checksum);
        }
        @Override protected void onStoreStream(byte[] bytes, int offset, int length) throws IOException { check.check(); }
        @Override protected void onObjectHeader(Source source, byte[] bytes, int offset, int length) throws IOException { check.check(); }
        @Override protected void onObjectData(Source source, byte[] bytes, int offset, int length) throws IOException { check.check(); }
        @Override protected void onInflatedObjectData(PackedObjectInfo info, int type, byte[] data) throws IOException { check.check(); }
        @Override protected void onBeginOfsDelta(long offset, long base, long length) throws IOException { throw delta(); }
        @Override protected void onBeginRefDelta(long offset, AnyObjectId base, long length) throws IOException { throw delta(); }
        @Override protected boolean onAppendBase(int type, byte[] data, PackedObjectInfo info) throws IOException { throw delta(); }
        @Override protected void onEndThinPack() throws IOException { throw delta(); }
        @Override protected ObjectTypeAndSize seekDatabase(PackedObjectInfo info, ObjectTypeAndSize header) throws IOException {
            throw failure("Git pack verification requires a fresh private context");
        }
        @Override protected ObjectTypeAndSize seekDatabase(UnresolvedDelta delta, ObjectTypeAndSize header) throws IOException { throw delta(); }
        @Override protected int readDatabase(byte[] bytes, int offset, int length) throws IOException {
            throw failure("Git pack verification cannot read unverified stored pack bytes");
        }
        @Override protected boolean checkCRC(int crc) { return false; }
        private IOException delta() throws IOException { check.check(); return failure("Git portable history cannot contain delta objects"); }
    }
}
