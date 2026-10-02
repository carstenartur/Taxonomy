package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.errors.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.transport.BundleWriter;
import org.eclipse.jgit.util.Paths;
import org.eclipse.jgit.util.sha1.SHA1;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** A self-contained, parentless Git representation of one projected stand. */
public final class GitStandBundle {
    public record Limits(int maxTreeBytes, int maxMetadataBytes) {
        public Limits {
            if (maxTreeBytes < 1 || maxTreeBytes > 4 * 1_048_576 || maxMetadataBytes < 1 || maxMetadataBytes > 16 * 1_048_576)
                throw new IllegalArgumentException("Invalid stand bundle metadata limits");
        }
        public static Limits defaults() { return new Limits(4 * 1_048_576, 16 * 1_048_576); }
    }
    private final GitStandBackupSource.Stand stand;
    private final BackupCheckpoint checkpoint;
    private final Map<ObjectId, ObjectData> objects;
    private final ObjectId head;

    private GitStandBundle(GitStandBackupSource.Stand stand, BackupCheckpoint checkpoint, Map<ObjectId, ObjectData> objects, ObjectId head) {
        this.stand = stand; this.checkpoint = checkpoint; this.objects = Map.copyOf(objects); this.head = head;
    }

    /** The source view and its installation fence must remain live through the final write. */
    public static GitStandBundle capture(GitStandBackupSource.Stand stand, Instant timestamp, BackupCheckpoint checkpoint) throws IOException {
        return capture(stand, timestamp, Limits.defaults(), checkpoint);
    }
    public static GitStandBundle capture(GitStandBackupSource.Stand stand, Instant timestamp, Limits limits, BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(timestamp); Objects.requireNonNull(limits);
            BackupCheckpoint check = () -> stand.check(checkpoint); check.check();
            var builder = new Graph(limits, check); var root = new Directory();
            for (var file : stand.files()) {
                check.check(); var hash = digest(Constants.OBJ_BLOB, file.length());
                stand.copy(file, new OutputStream() {
                    @Override public void write(int b) { hash.update((byte) b); }
                    @Override public void write(byte[] bytes, int offset, int length) { hash.update(bytes, offset, length); }
                }, check);
                ObjectId id = hash.toObjectId(); builder.add(id, new ObjectData(Constants.OBJ_BLOB, null, file));
                builder.addPath(root, file, id);
            }
            var commit = new CommitBuilder(); commit.setTreeId(builder.tree(root));
            var author = new PersonIdent("Taxonomy Export", "backup@taxonomy.invalid", timestamp, ZoneOffset.UTC);
            commit.setAuthor(author); commit.setCommitter(author); commit.setMessage("Portable stand\n");
            ObjectId head = builder.metadata(Constants.OBJ_COMMIT, commit.build()); check.check();
            return new GitStandBundle(stand, checkpoint, builder.objects, head);
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }
    public String head() { return head.name(); }

    /** JGit serializes only the private synthetic graph, directly into protected capture staging. */
    public BackupEntry write(String path, ComponentSink sink) throws IOException {
        try {
            BackupCheckpoint check = () -> { stand.check(checkpoint); stand.check(sink::checkpoint); }; check.check();
            return GitTreeCapture.writeGenerated(path, sink, output -> {
                check.check();
                try (var reader = new Reader(check)) {
                    var config = new PackConfig(); config.setDeltaCompress(false); config.setReuseObjects(false);
                    config.setReuseDeltas(false); config.setBuildBitmaps(false); config.setThreads(1);
                    var writer = new BundleWriter(reader); writer.setPackConfig(config);
                    writer.include("refs/heads/stand", head); writer.include("HEAD", head);
                    writer.writeBundle(NullProgressMonitor.INSTANCE, output); check.check();
                }
            });
        } catch (IOException | RuntimeException problem) { throw sanitized(problem); }
    }

    private final class Reader extends ObjectReader {
        private final BackupCheckpoint checkpoint;
        private boolean active = true;
        Reader(BackupCheckpoint checkpoint) { this.checkpoint = checkpoint; }
        private void check() throws IOException {
            if (!active) throw failure("Synthetic Git reader is closed"); checkpoint.check();
            if (!active) throw failure("Synthetic Git reader is closed");
        }
        @Override public ObjectReader newReader() { return new Reader(() -> { check(); }); }
        @Override public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
            check(); return objects.keySet().stream().filter(key -> id.prefixCompare(key) == 0).toList();
        }
        @Override public ObjectLoader open(AnyObjectId id, int type) throws IOException {
            check(); var object = objects.get(id);
            if (object == null) throw new MissingObjectException(id.copy(), type);
            if (type != OBJ_ANY && type != object.type()) throw new IncorrectObjectTypeException(id.copy(), type);
            if (object.bytes() != null) return new ObjectLoader.SmallObject(object.type(), object.bytes());
            return new ObjectLoader() {
                @Override public int getType() { return Constants.OBJ_BLOB; }
                @Override public long getSize() { return object.file().length(); }
                @Override public boolean isLarge() { return true; }
                @Override public byte[] getCachedBytes() { throw new LargeObjectException(); }
                @Override public ObjectStream openStream() throws IOException { throw failure("Synthetic Git blobs require synchronous streaming"); }
                @Override public void copyTo(OutputStream output) throws IOException {
                    check(); stand.copy(object.file(), output, Reader.this::check); check();
                }
            };
        }
        @Override public Set<ObjectId> getShallowCommits() throws IOException { check(); return Set.of(); }
        @Override public void close() { active = false; }
    }

    private static SHA1 digest(int type, long length) {
        var digest = SHA1.newInstance().setDetectCollision(true);
        digest.update((Constants.typeString(type) + " " + length + "\0").getBytes(StandardCharsets.US_ASCII)); return digest;
    }
    private record ObjectData(int type, byte[] bytes, GitStandBackupSource.File file) { }
    private static final class Directory { final Map<String, TreeEntry> entries = new HashMap<>(); int bytes; }
    private record TreeEntry(byte[] name, FileMode mode, Directory directory, ObjectId blob) { }

    private static final class Graph {
        final Limits limits; final BackupCheckpoint checkpoint; final Map<ObjectId, ObjectData> objects = new HashMap<>(); int metadata;
        Graph(Limits limits, BackupCheckpoint checkpoint) { this.limits = limits; this.checkpoint = checkpoint; }
        void addPath(Directory root, GitStandBackupSource.File file, ObjectId blob) throws IOException {
            var segments = file.path().split("/"); var directory = root;
            for (int i = 0; i < segments.length; i++) {
                checkpoint.check(); String name = segments[i]; boolean last = i == segments.length - 1;
                var prior = directory.entries.get(name);
                if (prior == null) {
                    byte[] encoded = name.getBytes(StandardCharsets.UTF_8); var mode = last ? FileMode.fromBits(file.mode()) : FileMode.TREE;
                    int size = TreeFormatter.entrySize(mode, encoded.length);
                    if (size > limits.maxTreeBytes() - directory.bytes) throw failure("Stand bundle tree metadata limit exceeded");
                    reserve(size); directory.bytes += size;
                    prior = new TreeEntry(encoded, mode, last ? null : new Directory(), last ? blob : null); directory.entries.put(name, prior);
                }
                if (!last) directory = prior.directory();
            }
        }
        ObjectId tree(Directory directory) throws IOException {
            checkpoint.check(); var formatter = new TreeFormatter(directory.bytes);
            var entries = new ArrayList<>(directory.entries.values());
            entries.sort((a, b) -> Paths.compare(a.name(), 0, a.name().length, a.mode().getBits(), b.name(), 0, b.name().length, b.mode().getBits()));
            for (var entry : entries) { checkpoint.check(); formatter.append(entry.name(), entry.mode(), entry.directory() == null ? entry.blob() : tree(entry.directory())); }
            byte[] bytes = formatter.toByteArray(); var hash = digest(Constants.OBJ_TREE, bytes.length); hash.update(bytes); ObjectId id = hash.toObjectId();
            add(id, new ObjectData(Constants.OBJ_TREE, bytes, null)); return id;
        }
        ObjectId metadata(int type, byte[] bytes) throws IOException {
            reserve(bytes.length); var hash = digest(type, bytes.length); hash.update(bytes); ObjectId id = hash.toObjectId();
            add(id, new ObjectData(type, bytes, null)); return id;
        }
        void reserve(int count) throws IOException {
            if (count > limits.maxMetadataBytes() - metadata) throw failure("Stand bundle total metadata limit exceeded"); metadata += count;
        }
        void add(ObjectId id, ObjectData data) throws IOException {
            var previous = objects.putIfAbsent(id, data);
            if (previous != null && (previous.type() != data.type() || !Arrays.equals(previous.bytes(), data.bytes())
                    || data.file() != null && (previous.file().length() != data.file().length() || !previous.file().sha256().equals(data.file().sha256()))))
                throw failure("Synthetic Git object ID collision");
        }
    }
}
