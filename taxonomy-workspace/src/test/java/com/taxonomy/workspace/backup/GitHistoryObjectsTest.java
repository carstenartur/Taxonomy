package com.taxonomy.workspace.backup;

import com.taxonomy.backup.BackupCheckpoint;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GitHistoryObjectsTest {
    private static final BackupCheckpoint CHECK = () -> { };
    private static final GitHistoryCapture.Limits LIMITS = GitHistoryCapture.Limits.defaults();

    @Test void lfsPointersAreRejectedEvenWhenTheStorageReturnsOneBytePerRead() throws Exception {
        try (var repository = new AuditedRepository()) {
            ObjectId blob = insert(repository, Constants.OBJ_BLOB,
                    "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 91\n");
            repository.chunkSize = 1;
            try (var reader = GitHistoryObjects.inventoryReader(repository, LIMITS, CHECK)) {
                assertThatThrownBy(() -> GitHistoryObjects.verify(reader, blob, CHECK))
                        .isInstanceOf(IOException.class).hasMessageContaining("LFS");
            }
        }
    }

    @Test void anObjectOutsideTheCapturedClosureIsRejectedBeforeOpeningItsStorage() throws Exception {
        try (var repository = new AuditedRepository()) {
            ObjectId inside = insert(repository, Constants.OBJ_BLOB, "inside");
            ObjectId outside = insert(repository, Constants.OBJ_BLOB, "outside-private-object");
            repository.audited = outside;
            try (var reader = GitHistoryObjects.capturedReader(repository, LIMITS,
                    Map.of(inside, new GitHistoryObjects.Proof(Constants.OBJ_BLOB, 6)), CHECK)) {
                assertThatThrownBy(() -> reader.open(outside)).isInstanceOf(IOException.class);
                assertThat(repository.auditedOpens).isZero();
            }
        }
    }

    @Test void corruptMetadataIsRejectedBeforeAParserCanUseItsReferences() throws Exception {
        try (var repository = new AuditedRepository()) {
            String original = "tree " + "a".repeat(40) + "\nauthor A <a@example.invalid> 1 +0000\ncommitter A <a@example.invalid> 1 +0000\n\noriginal\n";
            ObjectId commit = insert(repository, Constants.OBJ_COMMIT, original);
            repository.audited = commit;
            repository.replacement = original.replace("original", "modified").getBytes(StandardCharsets.UTF_8);
            try (var reader = GitHistoryObjects.inventoryReader(repository, LIMITS, CHECK)) {
                assertThatThrownBy(() -> reader.open(commit)).isInstanceOf(IOException.class)
                        .hasMessageContaining("identity");
            }
        }
    }

    @Test void cancellationDuringACapturedBlobStopsItsSynchronousCopy() throws Exception {
        try (var repository = new AuditedRepository()) {
            ObjectId blob = insert(repository, Constants.OBJ_BLOB, "x".repeat(200_000));
            var output = new ByteArrayOutputStream(); var checkpoints = new AtomicInteger();
            try (var reader = GitHistoryObjects.capturedReader(repository, LIMITS,
                    Map.of(blob, new GitHistoryObjects.Proof(Constants.OBJ_BLOB, 200_000)),
                    () -> { if (checkpoints.incrementAndGet() == 7) throw new IOException("cancelled"); })) {
                assertThatThrownBy(() -> reader.open(blob).copyTo(output)).isInstanceOf(IOException.class);
                assertThat(output.size()).isPositive().isLessThan(200_000);
            }
        }
    }

    private static ObjectId insert(Repository repository, int type, String content) throws IOException {
        try (var inserter = repository.newObjectInserter()) {
            ObjectId result = inserter.insert(type, content.getBytes(StandardCharsets.UTF_8)); inserter.flush(); return result;
        }
    }

    /** Actual JGit storage, with observable reads and explicit corruption/short-read injection. */
    private static final class AuditedRepository extends InMemoryRepository {
        ObjectId audited; int auditedOpens; int chunkSize = Integer.MAX_VALUE; byte[] replacement;
        AuditedRepository() { super(new DfsRepositoryDescription("history-objects-" + UUID.randomUUID())); }
        @Override public ObjectReader newObjectReader() { return audit(super.newObjectReader()); }
        private ObjectReader audit(ObjectReader delegate) {
            return new ObjectReader() {
                @Override public ObjectReader newReader() { return audit(delegate.newReader()); }
                @Override public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException { return delegate.resolve(id); }
                @Override public Set<ObjectId> getShallowCommits() throws IOException { return delegate.getShallowCommits(); }
                @Override public void setStreamFileThreshold(int threshold) { super.setStreamFileThreshold(threshold); delegate.setStreamFileThreshold(threshold); }
                @Override public ObjectLoader open(AnyObjectId id, int type) throws IOException {
                    if (id.equals(audited)) auditedOpens++;
                    var source = delegate.open(id, type);
                    if (id.equals(audited) && replacement != null) return new ObjectLoader.SmallObject(source.getType(), replacement);
                    if (chunkSize == Integer.MAX_VALUE) return source;
                    return new ObjectLoader() {
                        @Override public int getType() { return source.getType(); }
                        @Override public long getSize() { return source.getSize(); }
                        @Override public byte[] getCachedBytes() { return source.getCachedBytes(); }
                        @Override public ObjectStream openStream() throws IOException {
                            return new ObjectStream.Filter(source.getType(), source.getSize(), new FilterInputStream(source.openStream()) {
                                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                    return super.read(bytes, offset, Math.min(length, chunkSize));
                                }
                            });
                        }
                    };
                }
                @Override public void close() { delegate.close(); }
            };
        }
    }
}
