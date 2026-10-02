package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.*;

class GitTreeCaptureTest {
    @TempDir Path temporary;
    private static final GitTreeCapture.Limits LIMITS = new GitTreeCapture.Limits(100, 1_000_000, 2_000_000);
    private static final BackupCheckpoint CHECK = () -> { };

    @Test void pinsOnlyTheSelectedTreeAndCopiesExactBinaryBytesAndModes() throws Exception {
        try (var repository = memory()) {
            var old = commit(repository, tree(repository, "removed.txt", FileMode.REGULAR_FILE, bytes("OLD-SECRET")));
            byte[] content = new byte[200_000]; new Random(1234).nextBytes(content);
            var selected = commit(repository, tree(repository, "run.sh", FileMode.EXECUTABLE_FILE, content), old);
            ref(repository, selected);
            var captured = capture(repository, selected);
            assertThat(captured.commit()).isEqualTo(selected.name());
            assertThat(captured.files()).hasSize(1);
            var file = captured.files().getFirst();
            assertThat(file.path()).isEqualTo("run.sh");
            assertThat(file.mode()).isEqualTo(FileMode.EXECUTABLE_FILE.getBits());
            assertThat(file.length()).isEqualTo(content.length);
            assertThat(file.sha256()).isEqualTo(hash(content));
            ref(repository, commit(repository, tree(repository, "other.txt", FileMode.REGULAR_FILE, bytes("NEW")), selected));
            var output = new ByteArrayOutputStream(); captured.copy(file, output, CHECK);
            assertThat(output.toByteArray()).isEqualTo(content);
            assertThatThrownBy(() -> captured.files().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void selectedTreeDoesNotNeedOrReadItsAncestry() throws Exception {
        try (var repository = memory()) {
            var selected = commit(repository, tree(repository, "current.txt", FileMode.REGULAR_FILE, bytes("current")), ObjectId.fromString("1".repeat(40)));
            assertThat(capture(repository, selected).files()).extracting(GitTreeCapture.File::path).containsExactly("current.txt");
        }
    }

    @Test void emptyTreeIsAValidExactStand() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var tree = inserter.insert(new TreeFormatter()); inserter.flush();
            var captured = capture(repository, commit(repository, tree));
            assertThat(captured.tree()).isEqualTo(tree.name()); assertThat(captured.files()).isEmpty();
        }
    }

    @Test void keepsEmptyFilesAndDirectoryStructure() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var child = tree(repository, "empty.txt", FileMode.REGULAR_FILE, new byte[0]);
            var root = new TreeFormatter(); root.append("folder", FileMode.TREE, child);
            var id = inserter.insert(root); inserter.flush();
            var captured = capture(repository, commit(repository, id));
            assertThat(captured.files()).extracting(GitTreeCapture.File::path).containsExactly("folder/empty.txt");
            var sink = new Sink(); assertThat(captured.writeFile(captured.files().getFirst(), "files/empty.txt", sink).length()).isZero();
        }
    }

    @Test void rejectsExcessiveTreeDepthBeforeUnboundedRecursion() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var tree = tree(repository, "file", FileMode.REGULAR_FILE, bytes("x"));
            for (int i = 0; i < 33; i++) {
                var parent = new TreeFormatter(); parent.append("d", FileMode.TREE, tree); tree = inserter.insert(parent);
            }
            inserter.flush(); var selected = commit(repository, tree);
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @Test void boundsCommitMetadataBeforeParsingIt() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var tree = tree(repository, "file", FileMode.REGULAR_FILE, bytes("x"));
            byte[] raw = bytes("tree " + tree.name() + "\nauthor A <a@b.test> 1 +0000\ncommitter A <a@b.test> 1 +0000\n\n" + "x".repeat(1_048_576));
            var selected = inserter.insert(Constants.OBJ_COMMIT, raw); inserter.flush();
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(ints = {Constants.OBJ_COMMIT, Constants.OBJ_TREE})
    void oversizedMetadataIsRefusedBeforeTheProviderCanMaterializeIt(int type) throws Exception {
        try (var repository = new AuditedRepository()) {
            var tree = tree(repository, "file", FileMode.REGULAR_FILE, bytes("payload")); var selected = commit(repository, tree);
            repository.oversized = type == Constants.OBJ_COMMIT ? selected : tree;
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
            assertThat(repository.oversizedOpens).as("size rejection must precede an eager provider open").isZero();
        }
    }

    @Test void eachPrivateReaderUsesStreamingBeforeOpeningLargeBlobs() throws Exception {
        try (var repository = new AuditedRepository()) {
            var selected = commit(repository, tree(repository, "large", FileMode.REGULAR_FILE, new byte[200_000]));
            var captured = capture(repository, selected);
            captured.copy(captured.files().getFirst(), OutputStream.nullOutputStream(), CHECK);
            assertThat(repository.eagerBlobOpens).as("capture and copy must avoid the provider's default whole-blob threshold").isZero();
            assertThat(repository.blobOpens).isEqualTo(2);
        }
    }

    @Test void rejectsNonCommitObjectsAndMissingRequiredBlobs() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, bytes("x"));
            var root = new TreeFormatter(); root.append("missing", FileMode.REGULAR_FILE, ObjectId.fromString("2".repeat(40)));
            var tree = inserter.insert(root); inserter.flush(); var selected = commit(repository, tree);
            assertThatThrownBy(() -> capture(repository, blob)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @Test void limitsCannotDisableTheMetadataBoundOrUseNegativeSizes() {
        assertThatThrownBy(() -> new GitTreeCapture.Limits(0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitTreeCapture.Limits(10_001, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitTreeCapture.Limits(1, -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitTreeCapture.Limits(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"link", "submodule"})
    void refusesFilesThatNeedExternalOrFilesystemDependencies(String type) throws Exception {
        try (var repository = memory()) {
            var mode = type.equals("link") ? FileMode.SYMLINK : FileMode.GITLINK;
            var selected = commit(repository, tree(repository, "dependency", mode, bytes("../outside")));
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {".git", "CON.txt", "trailing.", "bad:name", "bad\\name", "e\u0301.txt"})
    void refusesNonPortablePaths(String path) throws Exception {
        try (var repository = memory()) {
            var selected = commit(repository, tree(repository, path, FileMode.REGULAR_FILE, bytes("data")));
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @Test void rejectsCaseCollisionsIncludingDirectoryPrefixes() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var a = tree(repository, "a.txt", FileMode.REGULAR_FILE, bytes("a"));
            var b = tree(repository, "b.txt", FileMode.REGULAR_FILE, bytes("b"));
            var root = new TreeFormatter(); root.append("Dir", FileMode.TREE, a); root.append("dir", FileMode.TREE, b);
            var tree = inserter.insert(root); inserter.flush();
            assertThatThrownBy(() -> capture(repository, commit(repository, tree))).isInstanceOf(IOException.class);
        }
    }

    @Test void rejectsNonUtf8TreeNamesInsteadOfReplacingTheirBytes() throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, bytes("x"));
            var raw = new ByteArrayOutputStream(); raw.write(bytes("100644 ")); raw.write(0xff); raw.write(0); blob.copyRawTo(raw);
            var tree = inserter.insert(Constants.OBJ_TREE, raw.toByteArray()); inserter.flush();
            assertThatThrownBy(() -> capture(repository, commit(repository, tree))).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"https://git-lfs.github.com/spec/v1", "https://hawser.github.com/spec/v1"})
    void lfsPointerDoesNotCountAsTheExternalFile(String version) throws Exception {
        try (var repository = memory()) {
            var selected = commit(repository, tree(repository, "image.bin", FileMode.REGULAR_FILE,
                    bytes("version " + version + "\noid sha256:" + "a".repeat(64) + "\nsize 1024\n")));
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"file", "total", "count"})
    void enforcesCaptureLimitsBeforeReturningEvidence(String limit) throws Exception {
        try (var repository = memory(); var inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, bytes("12345"));
            var tree = new TreeFormatter(); tree.append("a", FileMode.REGULAR_FILE, blob); tree.append("b", FileMode.REGULAR_FILE, blob);
            var root = inserter.insert(tree); inserter.flush(); var selected = commit(repository, root);
            var limits = switch (limit) {
                case "file" -> new GitTreeCapture.Limits(10, 4, 100);
                case "total" -> new GitTreeCapture.Limits(10, 10, 9);
                default -> new GitTreeCapture.Limits(1, 10, 100);
            };
            assertThatThrownBy(() -> GitTreeCapture.capture(repository, selected.name(), limits, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"HEAD", "draft", "1234567", "0"})
    void acceptsOnlyFullCommitIdsNotRevisionExpressions(String revision) throws Exception {
        try (var repository = memory()) {
            assertThatThrownBy(() -> GitTreeCapture.capture(repository, revision, LIMITS, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void cannotInventFilesOrReuseAFileFromADifferentCapturedTree() throws Exception {
        try (var repository = memory()) {
            var captured = capture(repository, commit(repository, tree(repository, "one", FileMode.REGULAR_FILE, bytes("one"))));
            var another = capture(repository, commit(repository, tree(repository, "two", FileMode.REGULAR_FILE, bytes("two"))));
            assertThatThrownBy(() -> captured.copy(another.files().getFirst(), new ByteArrayOutputStream(), CHECK)).isInstanceOf(IOException.class);
            var real = captured.files().getFirst();
            var forged = new GitTreeCapture.File("elsewhere", real.objectId(), real.mode(), real.length(), real.sha256());
            assertThatThrownBy(() -> captured.copy(forged, new ByteArrayOutputStream(), CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void verifiesSourceBytesAgainAndNeverTrustsAStoredObjectNameAlone() throws Exception {
        try (var repository = FileRepositoryBuilder.create(temporary.resolve("source.git").toFile())) {
            repository.create(true);
            var selected = commit(repository, tree(repository, "file", FileMode.REGULAR_FILE, bytes("original")));
            var captured = capture(repository, selected); var file = captured.files().getFirst();
            corruptLooseBlob(repository, file.objectId(), bytes("modified"));
            assertThatThrownBy(() -> captured.copy(file, new ByteArrayOutputStream(), CHECK)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> capture(repository, selected)).isInstanceOf(IOException.class);
        }
    }

    @Test void cancellationDuringAFileStopsBeforeItsCompletePayload() throws Exception {
        try (var repository = memory()) {
            var selected = commit(repository, tree(repository, "large", FileMode.REGULAR_FILE, new byte[200_000]));
            var captured = capture(repository, selected); var output = new ByteArrayOutputStream(); var calls = new AtomicInteger();
            try {
                assertThatThrownBy(() -> captured.copy(captured.files().getFirst(), output, () -> {
                    if (calls.incrementAndGet() == 4) throw new InterruptedIOException("private details");
                })).isInstanceOf(InterruptedIOException.class).hasMessageNotContaining("private details");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(output.size()).isLessThan(200_000);
            } finally { Thread.interrupted(); }
        }
    }

    @Test void generatedSinkReceivesVerifiedBytesAndItsReceiptMustAgree() throws Exception {
        try (var repository = memory()) {
            var captured = capture(repository, commit(repository, tree(repository, "file", FileMode.REGULAR_FILE, bytes("payload"))));
            var sink = new Sink(); var entry = captured.writeFile(captured.files().getFirst(), "files/repo/file", sink);
            assertThat(sink.bytes.toByteArray()).isEqualTo(bytes("payload"));
            assertThat(entry).isEqualTo(new BackupEntry("files/repo/file", 7, hash(bytes("payload"))));
            sink.badReceipt = true;
            assertThatThrownBy(() -> captured.writeFile(captured.files().getFirst(), "files/repo/file", sink)).isInstanceOf(IOException.class);
        }
    }

    @Test void sinkCannotClaimSuccessWithoutRunningTheProducer() throws Exception {
        try (var repository = memory()) {
            var captured = capture(repository, commit(repository, tree(repository, "file", FileMode.REGULAR_FILE, bytes("payload"))));
            var sink = new Sink(); sink.skipProducer = true;
            var file = captured.files().getFirst(); sink.override = new BackupEntry("files/repo/file", file.length(), file.sha256());
            assertThatThrownBy(() -> captured.writeFile(captured.files().getFirst(), "files/repo/file", sink)).isInstanceOf(IOException.class);
        }
    }

    @Test void sinkCannotHideASecondProducerInvocationAfterACompleteFirstCopy() throws Exception {
        try (var repository = memory()) {
            var captured = capture(repository, commit(repository, tree(repository, "file", FileMode.REGULAR_FILE, bytes("payload"))));
            var sink = new Sink(); sink.repeatProducer = true;
            assertThatThrownBy(() -> captured.writeFile(captured.files().getFirst(), "files/repo/file", sink)).isInstanceOf(IOException.class);
        }
    }

    private static InMemoryRepository memory() { return new InMemoryRepository(new DfsRepositoryDescription("capture")); }
    private static GitTreeCapture capture(Repository repository, ObjectId commit) throws IOException {
        var capture = GitTreeCapture.capture(repository, commit.name(), LIMITS, CHECK);
        assertThat(capture).as("the exact Git tree must be captured").isNotNull(); return capture;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static ObjectId tree(Repository repository, String name, FileMode mode, byte[] data) throws IOException {
        try (var inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, data); var tree = new TreeFormatter(); tree.append(name, mode, blob);
            var result = inserter.insert(tree); inserter.flush(); return result;
        }
    }
    private static ObjectId commit(Repository repository, ObjectId tree, ObjectId... parents) throws IOException {
        try (var inserter = repository.newObjectInserter()) {
            var commit = new CommitBuilder(); commit.setTreeId(tree); commit.setParentIds(parents);
            var who = new PersonIdent("Original Author", "author@example.test"); commit.setAuthor(who); commit.setCommitter(who); commit.setMessage("source commit");
            var result = inserter.insert(commit); inserter.flush(); return result;
        }
    }
    private static void ref(Repository repository, ObjectId target) throws IOException {
        var update = repository.updateRef("refs/heads/draft"); update.setNewObjectId(target); update.setForceUpdate(true);
        assertThat(update.update()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.FORCED);
    }
    private static void corruptLooseBlob(Repository repository, String id, byte[] bytes) throws IOException {
        Path path = repository.getDirectory().toPath().resolve("objects").resolve(id.substring(0, 2)).resolve(id.substring(2));
        assertThat(path.toFile().setWritable(true)).as("make the test-owned loose object writable for corruption injection").isTrue();
        try (var output = new DeflaterOutputStream(Files.newOutputStream(path))) {
            output.write(bytes("blob " + bytes.length + "\0")); output.write(bytes);
        }
    }
    private static class Sink implements ComponentSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); boolean badReceipt; boolean skipProducer; boolean repeatProducer; BackupEntry override;
        @Override public BackupEntry write(String path, InputStream input) { throw new AssertionError("Use the generated-entry stream"); }
        @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
            bytes.reset(); if (!skipProducer) producer.write(bytes);
            if (repeatProducer) { try { producer.write(bytes); } catch (IOException ignored) { } }
            if (override != null) return override;
            try { return new BackupEntry(path, badReceipt ? bytes.size() + 1 : bytes.size(), hash(bytes.toByteArray())); }
            catch (IOException e) { throw e; } catch (Exception e) { throw new AssertionError(e); }
        }
    }

    /** Real JGit storage with observable allocation boundaries, including a declared oversized source object. */
    private static final class AuditedRepository extends InMemoryRepository {
        ObjectId oversized; int oversizedOpens; int eagerBlobOpens; int blobOpens;
        AuditedRepository() { super(new DfsRepositoryDescription("audited-capture")); }
        @Override public ObjectReader newObjectReader() { return audit(super.newObjectReader()); }
        private ObjectReader audit(ObjectReader delegate) {
            return new ObjectReader() {
                @Override public ObjectReader newReader() { return audit(delegate.newReader()); }
                @Override public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException { return delegate.resolve(id); }
                @Override public Set<ObjectId> getShallowCommits() throws IOException { return delegate.getShallowCommits(); }
                @Override public long getObjectSize(AnyObjectId id, int type) throws IOException {
                    return id.equals(oversized) ? 6L * 1_048_576 : delegate.getObjectSize(id, type);
                }
                @Override public void setStreamFileThreshold(int threshold) { super.setStreamFileThreshold(threshold); delegate.setStreamFileThreshold(threshold); }
                @Override public ObjectLoader open(AnyObjectId id, int type) throws IOException {
                    var loader = delegate.open(id, type);
                    if (loader.getType() == Constants.OBJ_BLOB) {
                        blobOpens++; if (loader.getSize() > 8_192 && !loader.isLarge()) eagerBlobOpens++;
                    }
                    if (!id.equals(oversized)) return loader;
                    oversizedOpens++;
                    return new ObjectLoader() {
                        @Override public int getType() { return loader.getType(); }
                        @Override public long getSize() { return 6L * 1_048_576; }
                        @Override public byte[] getCachedBytes() { return loader.getCachedBytes(); }
                        @Override public ObjectStream openStream() throws IOException { return loader.openStream(); }
                    };
                }
                @Override public void close() { delegate.close(); }
            };
        }
    }
}
