package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import com.taxonomy.workspace.storage.DslGitRepository;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class GitStandBundleTest {
    @TempDir Path temporary;
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repo-a", "private-a");
    private static final GitStandBackupSource.Limits LIMITS = new GitStandBackupSource.Limits(new GitTreeCapture.Limits(100, 1_000_000, 2_000_000), 100_000);
    private static final BackupCheckpoint CHECK = () -> { };
    private static final Instant AT = Instant.parse("2026-01-02T03:04:05Z");

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"CURRENT_STATE", "SELECTED_VERSION"})
    void nativeGitCanCloneInspectAndExtendExactlyOneProjectedRoot(BackupProfile profile) throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            String old = commit(f, Map.of("removed.txt", bytes("OLD-SECRET")));
            var files = new HashMap<String, byte[]>(); files.put("architecture.taxdsl", bytes("SELECTED embedded-secret"));
            files.put("run.sh", bytes("#!/bin/sh\nexit 0\n")); files.put("dir/a.txt", bytes("duplicate"));
            files.put("dir.earlier", bytes("duplicate")); files.put("dir/Äpfel.txt", new byte[0]);
            String selected = commit(f, files); String current = commit(f, Map.of("architecture.taxdsl", bytes("CURRENT embedded-secret"), "run.sh", bytes("#!/bin/sh\nexit 0\n")));
            f.saved(KEY, "draft", "SAVED embedded-secret", 3, current, 1, null);
            String root;
            Path bundle = temporary.resolve("stand.bundle");
            try (var stand = f.source().open(authorization(profile, selected), KEY, LIMITS, CHECK)) {
                var captured = GitStandBundle.capture(stand, AT, CHECK); root = captured.head();
                var sink = new Sink(); var receipt = captured.write("repositories/stand.bundle", sink);
                assertThat(receipt.length()).isEqualTo(sink.bytes.size()); Files.write(bundle, sink.bytes.toByteArray());
            }
            git("clone", bundle.toString(), "checkout");
            git("-C", "checkout", "bundle", "verify", bundle.toString());
            git("-C", "checkout", "fsck", "--full", "--strict");
            assertThat(git("-C", "checkout", "rev-list", "--all", "--count")).isEqualTo("1\n");
            assertThat(git("-C", "checkout", "rev-parse", "HEAD")).isEqualTo(root + "\n");
            assertThat(git("-C", "checkout", "symbolic-ref", "HEAD")).isEqualTo("refs/heads/stand\n");
            assertThat(git("-C", "checkout", "cat-file", "-p", "HEAD")).contains("Taxonomy Export <backup@taxonomy.invalid>").doesNotContain("parent ", "source@example", "private commit");
            assertThat(Files.readString(temporary.resolve("checkout/architecture.taxdsl"))).isEqualTo(profile == BackupProfile.CURRENT_STATE ? "SAVED " : "SELECTED ");
            assertThat(git("-C", "checkout", "ls-tree", "-r", "HEAD")).contains("100755 blob").doesNotContain("removed.txt");
            if (profile == BackupProfile.SELECTED_VERSION) {
                assertThat(Files.readString(temporary.resolve("checkout/dir/a.txt"))).isEqualTo("duplicate");
                assertThat(Files.size(temporary.resolve("checkout/dir/Äpfel.txt"))).isZero();
                assertThat(git("-C", "checkout", "rev-parse", "HEAD:dir/a.txt")).isEqualTo(git("-C", "checkout", "rev-parse", "HEAD:dir.earlier"));
            }
            String objects = git("-C", "checkout", "cat-file", "--batch-all-objects", "--batch-check=%(objectname)");
            try (var formatter = new ObjectInserter.Formatter()) {
                assertThat(objects).doesNotContain(old, selected, current, formatter.idFor(Constants.OBJ_BLOB, bytes("OLD-SECRET")).name(),
                        formatter.idFor(Constants.OBJ_BLOB, bytes("SELECTED embedded-secret")).name(), formatter.idFor(Constants.OBJ_BLOB, bytes("SAVED embedded-secret")).name());
            }
            Files.writeString(temporary.resolve("checkout/architecture.taxdsl"), "edited\n");
            assertThat(git("-C", "checkout", "diff")).contains("+edited");
            git("-C", "checkout", "add", "architecture.taxdsl");
            git("-C", "checkout", "-c", "user.name=Offline User", "-c", "user.email=offline@example.test", "commit", "-m", "Offline edit");
            assertThat(git("-C", "checkout", "rev-list", "--all", "--count")).isEqualTo("2\n");
            try (var source = repository(f)) {
                assertThat(source.getGitRepository().exactRef("refs/heads/draft").getObjectId().name()).isEqualTo(current);
                assertThat(source.getGitRepository().exactRef("refs/heads/stand")).isNull();
            }
        }
    }

    @Test void anUnbornCurrentRepositoryStillProducesOneEmptyRootAndDeterministicMetadata() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            commit(f, Map.of()); f.sql("update user_workspace set current_branch='unborn'");
            try (var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK)) {
                var first = GitStandBundle.capture(stand, AT, CHECK); var again = GitStandBundle.capture(stand, AT, CHECK);
                assertThat(first.head()).isEqualTo(again.head()); assertThat(first.head()).isNotEqualTo(GitStandBundle.capture(stand, AT.plusSeconds(1), CHECK).head());
                var one = new Sink(); var two = new Sink(); first.write("repositories/stand.bundle", one); again.write("repositories/stand.bundle", two);
                assertThat(one.bytes.toByteArray()).isEqualTo(two.bytes.toByteArray()); Files.write(temporary.resolve("empty.bundle"), one.bytes.toByteArray());
            }
            git("clone", "--bare", "empty.bundle", "empty.git"); git("-C", "empty.git", "fsck", "--full", "--strict");
            assertThat(git("-C", "empty.git", "rev-list", "--all", "--count")).isEqualTo("1\n"); assertThat(git("-C", "empty.git", "ls-tree", "-r", "HEAD")).isEmpty();
        }
    }

    @Test void sourceLifetimeAndCancellationAlsoApplyToEmptyGraphs() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            commit(f, Map.of()); var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK);
            var cancelled = new AtomicBoolean(); BackupCheckpoint check = () -> { if (cancelled.get()) throw new IOException("PRIVATE-CANCELLATION"); };
            var captured = GitStandBundle.capture(stand, AT, check); cancelled.set(true);
            assertThatThrownBy(() -> captured.write("repositories/stand.bundle", new Sink())).isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("PRIVATE-CANCELLATION");
            cancelled.set(false); stand.close();
            assertThatThrownBy(() -> captured.write("repositories/stand.bundle", new Sink())).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> GitStandBundle.capture(stand, AT, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"skip", "twice", "thread", "path", "length", "hash", "swallow", "closed", "cancel"})
    void theSinkMustSynchronouslyConsumeOneVerifiedProducerAndReturnItsExactReceipt(String problem) throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            commit(f, Map.of("architecture.taxdsl", bytes("CURRENT embedded-secret")));
            try (var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK)) {
                var captured = GitStandBundle.capture(stand, AT, CHECK);
                var sink = new Sink() {
                    boolean running;
                    @Override public BackupEntry writeGenerated(String path, EntryWriter writer) throws IOException {
                        running = true;
                        if (problem.equals("skip")) return new BackupEntry(path, 0, hash(new byte[0]));
                        if (problem.equals("thread")) {
                            var other = new Thread(() -> { try { writer.write(bytes); } catch (IOException ignored) { } }); other.start();
                            try { other.join(10_000); } catch (InterruptedException interrupted) { throw new InterruptedIOException(); }
                            assertThat(other.isAlive()).isFalse(); return new BackupEntry(path, bytes.size(), hash(bytes.toByteArray()));
                        }
                        if (problem.equals("closed")) stand.close();
                        if (problem.equals("swallow")) {
                            try { writer.write(new OutputStream() { @Override public void write(int b) throws IOException { throw new IOException("PRIVATE-SINK-PATH"); } }); } catch (IOException ignored) { }
                            return new BackupEntry(path, 0, hash(new byte[0]));
                        }
                        var result = super.writeGenerated(path, writer);
                        if (problem.equals("twice")) try { writer.write(OutputStream.nullOutputStream()); } catch (IOException ignored) { }
                        return switch (problem) {
                            case "path" -> new BackupEntry("repositories/wrong.bundle", result.length(), result.sha256());
                            case "length" -> new BackupEntry(path, result.length() + 1, result.sha256());
                            case "hash" -> new BackupEntry(path, result.length(), "0".repeat(64));
                            default -> result;
                        };
                    }
                    @Override public void checkpoint() throws IOException { if (problem.equals("cancel") && running) throw new IOException("PRIVATE-CANCEL"); }
                };
                assertThatThrownBy(() -> captured.write("repositories/stand.bundle", sink)).isInstanceOf(IOException.class).hasNoCause().hasMessageNotContaining("PRIVATE-");
            }
        }
    }

    @Test void savedContentCannotChangeAfterTheSyntheticIdsWereComputed() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            String commit = commit(f, Map.of("architecture.taxdsl", bytes("OLD"))); f.saved(KEY, "draft", "SAVED", 2, commit, 1, null);
            try (var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK)) {
                var captured = GitStandBundle.capture(stand, AT, CHECK); f.sql("update editor_workspace set dsl='1:CHANGED'");
                assertThatThrownBy(() -> captured.write("repositories/stand.bundle", new Sink())).isInstanceOf(IOException.class);
            }
        }
    }

    @Test void metadataBudgetsCannotBeDisabledAndBoundBothDirectoriesAndTheRootCommit() throws Exception {
        assertThatThrownBy(() -> new GitStandBundle.Limits(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitStandBundle.Limits(4 * 1_048_576 + 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitStandBundle.Limits(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GitStandBundle.Limits(1, 16 * 1_048_576 + 1)).isInstanceOf(IllegalArgumentException.class);
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            commit(f, Map.of("folder/a", bytes("a"), "folder/b", bytes("b")));
            try (var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK)) {
                assertThatThrownBy(() -> GitStandBundle.capture(stand, AT, new GitStandBundle.Limits(32, 1000), CHECK)).isInstanceOf(IOException.class).hasMessageContaining("tree metadata limit");
                assertThatThrownBy(() -> GitStandBundle.capture(stand, AT, new GitStandBundle.Limits(1000, 50), CHECK)).isInstanceOf(IOException.class).hasMessageContaining("total metadata limit");
                assertThatThrownBy(() -> GitStandBundle.capture(stand, AT, new GitStandBundle.Limits(1000, 150), CHECK)).isInstanceOf(IOException.class).hasMessageContaining("total metadata limit");
            }
        }
    }

    @Test void expiredProducerAndOutputHandlesCannotWriteAfterTheSinkReturns() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            commit(f, Map.of("file.txt", bytes("content")));
            try (var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK)) {
                ComponentSink.EntryWriter[] escaped = {null}; var sink = new Sink() {
                    @Override public BackupEntry writeGenerated(String path, EntryWriter writer) { escaped[0] = writer; return new BackupEntry(path, 0, hash(new byte[0])); }
                };
                assertThatThrownBy(() -> GitStandBundle.capture(stand, AT, CHECK).write("repositories/stand.bundle", sink)).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> escaped[0].write(sink.bytes)).isInstanceOf(IOException.class); assertThat(sink.bytes.size()).isZero();
                OutputStream[] output = {null}; var honest = new Sink();
                GitTreeCapture.writeGenerated("repositories/known.bin", honest, stream -> { output[0] = stream; stream.write(bytes("safe")); stream.flush(); });
                assertThatThrownBy(() -> output[0].write(1)).isInstanceOf(IOException.class);
                assertThatThrownBy(() -> output[0].flush()).isInstanceOf(IOException.class); assertThat(honest.bytes.toString(UTF_8)).isEqualTo("safe");
            }
        }
    }

    @Test void cancellationDuringCompressedOutputRemainsInterruptedAndCannotYieldAReceipt() throws Exception {
        try (var f = new GitStandBackupSourceTest.Fixture()) {
            byte[] binary = new byte[200_000]; new Random(42).nextBytes(binary); commit(f, Map.of("binary", binary));
            try (var stand = f.source().open(authorization(BackupProfile.CURRENT_STATE, null), KEY, LIMITS, CHECK)) {
                var sink = new Sink() { @Override public void checkpoint() throws IOException { if (bytes.size() > 100) throw new InterruptedIOException("PRIVATE-CANCEL"); } };
                assertThatThrownBy(() -> GitStandBundle.capture(stand, AT, CHECK).write("repositories/stand.bundle", sink)).isInstanceOf(InterruptedIOException.class).hasNoCause().hasMessageNotContaining("PRIVATE-CANCEL");
                assertThat(Thread.currentThread().isInterrupted()).isTrue(); assertThat(sink.bytes.size()).isLessThan(binary.length);
            } finally { Thread.interrupted(); }
        }
    }

    private static DslGitRepository repository(GitStandBackupSourceTest.Fixture f) { return new DslGitRepository(new DefaultHibernateRepositoryFactory(f.persistence.factory), "ws-private-a"); }
    private static String commit(GitStandBackupSourceTest.Fixture f, Map<String, byte[]> files) throws Exception {
        try (var source = repository(f); var insert = source.getGitRepository().newObjectInserter()) {
            var index = DirCache.newInCore(); var builder = index.builder();
            for (var file : new TreeMap<>(files).entrySet()) {
                var entry = new DirCacheEntry(file.getKey()); entry.setFileMode(file.getKey().equals("run.sh") ? FileMode.EXECUTABLE_FILE : FileMode.REGULAR_FILE);
                entry.setObjectId(insert.insert(Constants.OBJ_BLOB, file.getValue())); builder.add(entry);
            }
            builder.finish(); var commit = new CommitBuilder(); commit.setTreeId(index.writeTree(insert));
            var repository = source.getGitRepository(); var previous = repository.exactRef("refs/heads/draft"); if (previous != null) commit.setParentId(previous.getObjectId());
            var actor = new PersonIdent("source", "source@example.test"); commit.setAuthor(actor); commit.setCommitter(actor); commit.setMessage("private commit metadata");
            var id = insert.insert(commit); insert.flush(); var ref = repository.updateRef("refs/heads/draft"); ref.setNewObjectId(id);
            assertThat(ref.update()).isIn(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD); return id.name();
        }
    }
    private static AuthorizedBackupRequest authorization(BackupProfile profile, String selected) {
        var request = new BackupRequest(profile, new BackupScope.Workspace(KEY.repositoryId(), KEY.workspaceId()), profile == BackupProfile.SELECTED_VERSION ? new BackupTime.SelectedVersion(Map.of(KEY, selected)) : new BackupTime.Current(), GitRepresentation.BUNDLE, SecretsSelection.EXCLUDE);
        return new AuthorizedBackupRequest(request, PrincipalId.create(), "checked", AT, EnumSet.allOf(BackupCapability.class));
    }
    private static byte[] bytes(String text) { return text.getBytes(UTF_8); }
    private static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception impossible) { throw new AssertionError(impossible); } }
    private static class Sink implements ComponentSink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        @Override public BackupEntry write(String path, InputStream input) { throw new AssertionError("Generated entries must use the protected producer path"); }
        @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException { producer.write(bytes); return new BackupEntry(path, bytes.size(), hash(bytes.toByteArray())); }
    }
    private String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + temporary.resolve("no-hooks"))); command.addAll(List.of(args));
        Path out = Files.createTempFile(temporary, "git-", ".out"), err = Files.createTempFile(temporary, "git-", ".err"), config = temporary.resolve("empty-git-config"); Files.writeString(config, "");
        var builder = new ProcessBuilder(command).directory(temporary.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile());
        var env = builder.environment(); env.keySet().removeIf(key -> key.startsWith("GIT_")); env.put("GIT_CONFIG_NOSYSTEM", "1"); env.put("GIT_CONFIG_GLOBAL", config.toString()); env.put("GIT_TERMINAL_PROMPT", "0");
        var process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("native Git timeout").isTrue();
            assertThat(process.exitValue()).as("Git %s: %s", args[0], Files.readString(err)).isZero(); return Files.readString(out);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
