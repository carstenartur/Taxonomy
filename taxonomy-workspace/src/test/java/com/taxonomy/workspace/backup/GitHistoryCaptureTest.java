package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class GitHistoryCaptureTest {
    private static final BackupCheckpoint CHECK = () -> { };
    @TempDir Path temporary;

    @Test void anOfflineBundlePreservesOriginalHistoryIncludingACommitWithoutABranch() throws Exception {
        String first, current, unreferenced, tag, notes;
        var contents = new Contents();
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            first = commit(git, "ORIGINAL", "first");
            current = commit(git, "CURRENT", "current");
            git.tag().setName("release").setAnnotated(true).setTagger(author()).setMessage("Original tag").call();
            tag = git.getRepository().resolve("refs/tags/release").name();
            try (var walk = new RevWalk(git.getRepository())) {
                git.notesAdd().setObjectId(walk.parseCommit(ObjectId.fromString(first))).setMessage("Original note").call();
            }
            notes = git.getRepository().resolve("refs/notes/commits").name();
            git.checkout().setCreateBranch(true).setName("deleted").setStartPoint(first).call();
            unreferenced = commit(git, "DATABASE-REFERENCED", "unreferenced");
            git.checkout().setName("main").call();
            git.branchDelete().setBranchNames("deleted").setForce(true).call();
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", "https://must-not-be-contacted.invalid/repo.git");
            config.setString("credential", null, "helper", "must-not-run");
            config.save();
            Map<String, String> before = refs(git.getRepository());
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(unreferenced),
                    Map.of("main", new SnapshotContext.WorkingState(3, current, 2)),
                    GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.state().refs()).containsEntry("refs/heads/main", current)
                    .containsEntry("refs/tags/release", tag).containsEntry("refs/notes/commits", notes);
            assertThat(capture.state().symbolicHead()).isEqualTo("refs/heads/main");
            assertThat(capture.state().requiredCommits()).contains(unreferenced);
            assertThat(capture.reflogs()).anySatisfy(entry -> assertThat(entry.newId()).isEqualTo(current));
            capture.write("repository-a", GitRepresentation.BUNDLE, contents);
            assertThat(refs(git.getRepository())).isEqualTo(before);
        }
        Files.move(source, temporary.resolve("source-disabled"));
        byte[] bundle = contents.entries.get("repositories/repository-a/history.bundle");
        assertThat(bundle).isNotNull();
        Path bundlePath = temporary.resolve("history.bundle");
        Files.write(bundlePath, bundle);
        String header = new String(bundle, StandardCharsets.ISO_8859_1).split("\n\n", 2)[0];
        assertThat(header.lines()).noneMatch(line -> line.startsWith("-"));
        Path restored = temporary.resolve("restored.git");
        git(temporary, "clone", "--mirror", bundlePath.toString(), restored.toString());
        git(temporary, "--git-dir=" + restored, "fsck", "--full");
        assertThat(git(temporary, "--git-dir=" + restored, "rev-parse", "refs/heads/main").strip()).isEqualTo(current);
        assertThat(git(temporary, "--git-dir=" + restored, "rev-parse", "refs/tags/release").strip()).isEqualTo(tag);
        assertThat(git(temporary, "--git-dir=" + restored, "rev-parse", "refs/notes/commits").strip()).isEqualTo(notes);
        assertThat(git(temporary, "--git-dir=" + restored, "show", first + ":architecture.taxdsl")).isEqualTo("ORIGINAL");
        assertThat(git(temporary, "--git-dir=" + restored, "show", unreferenced + ":architecture.taxdsl")).isEqualTo("DATABASE-REFERENCED");
        assertThat(git(temporary, "--git-dir=" + restored, "show", "-s", "--format=%an <%ae>", current).strip())
                .isEqualTo("Original Author <original@example.invalid>");
        assertThat(contents.entries).containsKey("data/workspace/git-history-repository-a.ndjson");
        assertThat(new String(contents.entries.get("data/workspace/git-history-repository-a.ndjson"), StandardCharsets.UTF_8))
                .contains("\"recordType\":\"reflog\"", "\"symbolicHead\":\"refs/heads/main\"", unreferenced)
                .doesNotContain("must-not-run", "must-not-be-contacted");
    }

    @ParameterizedTest @EnumSource(value = GitRepresentation.class, names = {"BARE", "WORKTREE"})
    void nativeFileRepositoriesHaveTheirOwnObjectsAndCanBeUsedOffline(GitRepresentation representation) throws Exception {
        var contents = new Contents(); String original;
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            commit(git, "FIRST", "first"); original = commit(git, "CURRENT", "current");
            var config = git.getRepository().getConfig();
            config.setString("remote", "production", "url", "https://do-not-copy.invalid/production.git");
            config.setString("credential", null, "helper", "must-not-run"); config.save();
            Files.writeString(source.resolve(".git/hooks/post-checkout"), "must-not-run");
            GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK)
                    .write("repository-a", representation, contents);
        }
        Files.move(source, temporary.resolve("source-disabled"));
        Path export = materialize(contents);
        Path repository = export.resolve("repositories/repository-a");
        Path gitDirectory = representation == GitRepresentation.BARE ? repository : repository.resolve(".git");
        git(temporary, "--git-dir=" + gitDirectory, "fsck", "--full");
        assertThat(git(temporary, "--git-dir=" + gitDirectory, "rev-parse", "HEAD").strip()).isEqualTo(original);
        assertThat(Files.readString(gitDirectory.resolve("config"))).doesNotContain("do-not-copy", "credential", "remote", "hooks");
        assertThat(gitDirectory.resolve("objects/info/alternates")).doesNotExist();
        assertThat(gitDirectory.resolve("hooks/post-checkout")).doesNotExist();
        assertThat(gitDirectory.resolve("logs/refs/heads/main")).isRegularFile();
        Path working = representation == GitRepresentation.WORKTREE ? repository : temporary.resolve("checkout");
        if (representation == GitRepresentation.BARE) git(temporary, "clone", repository.toString(), working.toString());
        assertThat(Files.readString(working.resolve("architecture.taxdsl"))).isEqualTo("CURRENT");
        assertThat(git(working, "status", "--porcelain")).isEmpty();
        try (var reopened = Git.open(working.toFile())) {
            var index = DirCache.read(reopened.getRepository());
            assertThat(index.getEntry("architecture.taxdsl").getObjectId().name())
                    .isEqualTo(reopened.getRepository().resolve("HEAD:architecture.taxdsl").name());
        }
        Files.writeString(working.resolve("architecture.taxdsl"), "OFFLINE CHANGE");
        git(working, "add", "architecture.taxdsl");
        git(working, "-c", "user.name=Offline Author", "-c", "user.email=offline@example.invalid", "commit", "-m", "Offline commit");
        assertThat(git(working, "rev-parse", "HEAD^1").strip()).isEqualTo(original);
    }

    @Test void anUnbornHeadPreservesOtherHistoryWithoutInventingACommit() throws Exception {
        var contents = new Contents(); String original;
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            original = commit(git, "OTHER BRANCH", "other");
            git.getRepository().updateRef("HEAD").link("refs/heads/unborn");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.state().symbolicHead()).isEqualTo("refs/heads/unborn");
            assertThat(capture.state().refs()).containsEntry("refs/heads/main", original).doesNotContainKeys("refs/heads/unborn", "HEAD");
            capture.write("repository-a", GitRepresentation.BARE, contents);
        }
        Path repository = materialize(contents).resolve("repositories/repository-a");
        assertThat(Files.readString(repository.resolve("HEAD"))).isEqualTo("ref: refs/heads/unborn\n");
        git(temporary, "--git-dir=" + repository, "fsck", "--full");
        assertThat(git(temporary, "--git-dir=" + repository, "rev-parse", "refs/heads/main").strip()).isEqualTo(original);
    }

    @Test void aDetachedHeadSurvivesFileRepositoryExport() throws Exception {
        var contents = new Contents(); String original;
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            original = commit(git, "DETACHED", "detached");
            git.checkout().setName(original).call();
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.state().symbolicHead()).isNull();
            assertThat(capture.state().refs()).containsEntry("HEAD", original);
            capture.write("repository-a", GitRepresentation.BARE, contents);
        }
        Path repository = materialize(contents).resolve("repositories/repository-a");
        assertThat(Files.readString(repository.resolve("HEAD"))).isEqualTo(original + "\n");
        git(temporary, "--git-dir=" + repository, "fsck", "--full");
    }

    @ParameterizedTest @EnumSource(value = GitRepresentation.class, names = {"BARE", "WORKTREE"})
    void aRepositoryWithoutItsFirstCommitIsStillAnOrdinaryOfflineRepository(GitRepresentation representation) throws Exception {
        var contents = new Contents();
        try (var source = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            var capture = GitHistoryCapture.capture(source.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.state().refs()).isEmpty();
            capture.write("repository-a", representation, contents);
        }
        Path repository = materialize(contents).resolve("repositories/repository-a");
        Path gitDirectory = representation == GitRepresentation.BARE ? repository : repository.resolve(".git");
        assertThat(gitDirectory.resolve("refs")).isDirectory();
        assertThat(Files.readString(gitDirectory.resolve("HEAD"))).isEqualTo("ref: refs/heads/main\n");
        git(temporary, "--git-dir=" + gitDirectory, "fsck", "--full");
        if (representation == GitRepresentation.WORKTREE) assertThat(git(repository, "status", "--porcelain")).isEmpty();
    }

    @Test void missingAndNonCommitDatabaseReferencesFailBeforeExport() throws Exception {
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            commit(git, "CURRENT", "current");
            String blob = git.getRepository().resolve("HEAD:architecture.taxdsl").name();
            for (String id : List.of("a".repeat(40), blob))
                assertThatThrownBy(() -> GitHistoryCapture.capture(git.getRepository(), Set.of(id), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK))
                        .isInstanceOf(IOException.class);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refs", "objects", "object-bytes", "total-bytes", "reflogs"})
    void declaredLimitsBoundCaptureBeforeAnyPayloadIsPublished(String limited) throws Exception {
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            commit(git, "CURRENT", "current");
            var limits = new GitHistoryCapture.Limits(limited.equals("refs") ? 1 : 100,
                    limited.equals("objects") ? 2 : 100, limited.equals("object-bytes") ? 1 : 1_000_000,
                    limited.equals("total-bytes") ? 1 : 1_000_000, limited.equals("reflogs") ? 0 : 100);
            assertThatThrownBy(() -> GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), limits, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void sourceMovementAndExpiredCaptureSessionsFailBeforeWriting() throws Exception {
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            commit(git, "CURRENT", "current");
            var live = new AtomicBoolean(true);
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK,
                    () -> { if (!live.get()) throw new IOException("private storage details"); });
            var contents = new Contents(); live.set(false);
            assertThatThrownBy(() -> capture.write("repository-a", GitRepresentation.BUNDLE, contents))
                    .isInstanceOf(IOException.class).hasMessageNotContaining("private storage details");
            assertThat(contents.entries).isEmpty(); live.set(true);
            commit(git, "NEW", "new");
            assertThatThrownBy(() -> capture.write("repository-a", GitRepresentation.BUNDLE, contents))
                    .isInstanceOf(IOException.class).hasMessageContaining("refs changed");
            assertThat(contents.entries).isEmpty();
        }
    }

    @Test void shallowHistoryAndMissingLfsContentsAreNeverDeclaredSelfContained() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            String head = commit(git, "CURRENT", "current");
            Files.writeString(source.resolve(".git/shallow"), head + "\n");
            assertThatThrownBy(() -> GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK))
                    .isInstanceOf(IOException.class).hasMessageContaining("Shallow");
            Files.delete(source.resolve(".git/shallow"));
        }
        try (var git = Git.open(source.toFile())) {
            commit(git, "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 99\n", "LFS");
            assertThatThrownBy(() -> GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK))
                    .isInstanceOf(IOException.class).hasMessageContaining("LFS");
        }
    }

    @Test void changingOnlyTheSymbolicHeadInvalidatesTheCaptureBeforeAnyEntry() throws Exception {
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            commit(git, "CURRENT", "current"); git.branchCreate().setName("same-object").call();
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            git.getRepository().updateRef("HEAD").link("refs/heads/same-object");
            var contents = new Contents();
            assertThatThrownBy(() -> capture.write("repository-a", GitRepresentation.BUNDLE, contents)).isInstanceOf(IOException.class);
            assertThat(contents.entries).isEmpty();
        }
    }

    @Test void aReflogOnlyChangeInvalidatesTheCaptureBeforeAnyEntry() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            String current = commit(git, "CURRENT", "current");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            Files.writeString(source.resolve(".git/logs/HEAD"), current + " " + current + " A <a@example.invalid> 1 +0000\tnew retained event\n", StandardOpenOption.APPEND);
            var contents = new Contents();
            assertThatThrownBy(() -> capture.write("repository-a", GitRepresentation.BUNDLE, contents)).isInstanceOf(IOException.class);
            assertThat(contents.entries).isEmpty();
        }
    }

    @Test void aRetainedReflogWhoseRefWasDeletedPreservesItsUnreferencedObjects() throws Exception {
        Path source = temporary.resolve("source"); String orphan;
        var contents = new Contents();
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            String main = commit(git, "CURRENT", "current");
            git.checkout().setCreateBranch(true).setName("removed").call(); orphan = commit(git, "RETAINED", "orphan");
            git.checkout().setName("main").call(); git.branchDelete().setBranchNames("removed").setForce(true).call();
            Files.delete(source.resolve(".git/logs/HEAD"));
            Files.writeString(source.resolve(".git/logs/refs/heads/removed"), main + " " + orphan + " Original Author <original@example.invalid> 1 +0000\tretained\n");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.reflogs()).anySatisfy(log -> {
                assertThat(log.ref()).isEqualTo("refs/heads/removed"); assertThat(log.newId()).isEqualTo(orphan);
            });
            capture.write("repository-a", GitRepresentation.BARE, contents);
        }
        Files.move(source, temporary.resolve("source-disabled"));
        Path repository = materialize(contents).resolve("repositories/repository-a");
        git(temporary, "--git-dir=" + repository, "fsck", "--full");
        assertThat(git(temporary, "--git-dir=" + repository, "show", orphan + ":architecture.taxdsl")).isEqualTo("RETAINED");
    }

    @Test void packedRefsUseGitByteOrderingForNonAsciiBranchNames() throws Exception {
        var contents = new Contents(); String original;
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            original = commit(git, "CURRENT", "current");
            git.branchCreate().setName("\ue000").call(); git.branchCreate().setName("\ud83d\ude00").call();
            GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK)
                    .write("repository-a", GitRepresentation.BARE, contents);
        }
        String packed = new String(contents.entries.get("repositories/repository-a/packed-refs"), StandardCharsets.UTF_8);
        assertThat(packed.indexOf("refs/heads/\ue000")).isLessThan(packed.indexOf("refs/heads/\ud83d\ude00"));
        Path repository = materialize(contents).resolve("repositories/repository-a");
        for (String branch : List.of("\ue000", "\ud83d\ude00"))
            assertThat(git(temporary, "--git-dir=" + repository, "rev-parse", "refs/heads/" + branch).strip()).isEqualTo(original);
    }

    @ParameterizedTest @EnumSource(value = GitRepresentation.class, names = {"BARE", "WORKTREE"})
    void symbolicAliasesRetainTheirImmediateTargetsInOrdinaryRepositories(GitRepresentation representation) throws Exception {
        var contents = new Contents();
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            commit(git, "CURRENT", "current");
            assertThat(git.getRepository().updateRef("refs/remotes/origin/HEAD").link("refs/heads/main")).isEqualTo(RefUpdate.Result.NEW);
            assertThat(git.getRepository().updateRef("refs/aliases/current").link("refs/remotes/origin/HEAD")).isEqualTo(RefUpdate.Result.NEW);
            GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK)
                    .write("repository-a", representation, contents);
        }
        Path repository = materialize(contents).resolve("repositories/repository-a");
        Path directory = representation == GitRepresentation.BARE ? repository : repository.resolve(".git");
        assertThat(git(temporary, "--git-dir=" + directory, "symbolic-ref", "--no-recurse", "refs/remotes/origin/HEAD").strip()).isEqualTo("refs/heads/main");
        assertThat(git(temporary, "--git-dir=" + directory, "symbolic-ref", "--no-recurse", "refs/aliases/current").strip()).isEqualTo("refs/remotes/origin/HEAD");
        assertThat(new String(contents.entries.get("data/workspace/git-history-repository-a.ndjson"), StandardCharsets.UTF_8))
                .contains("\"symbolicRefs\"", "refs/aliases/current");
        git(temporary, "--git-dir=" + directory, "fsck", "--full");
    }

    @Test void anAliasTargetChangeInvalidatesCaptureEvenWhenTheResolvedObjectIsUnchanged() throws Exception {
        try (var git = Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call()) {
            commit(git, "CURRENT", "current"); git.branchCreate().setName("same-object").call();
            git.getRepository().updateRef("refs/aliases/current").link("refs/heads/main");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            git.getRepository().updateRef("refs/aliases/current").link("refs/heads/same-object");
            var contents = new Contents();
            assertThatThrownBy(() -> capture.write("repository-a", GitRepresentation.BUNDLE, contents)).isInstanceOf(IOException.class);
            assertThat(contents.entries).isEmpty();
        }
    }

    @Test void aSingleOversizedRetainedReflogRecordIsRejectedBeforeUnboundedParsing() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            String current = commit(git, "CURRENT", "current");
            Files.writeString(source.resolve(".git/logs/HEAD"), current + " " + current + " A <a@example.invalid> 1 +0000\t" + "x".repeat(65_537) + "\n");
            assertThatThrownBy(() -> GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK))
                    .isInstanceOf(IOException.class).hasMessageContaining("reflog");
        }
    }

    private Path materialize(Contents contents) throws IOException {
        Path root = Files.createDirectory(temporary.resolve("export"));
        for (var entry : contents.entries.entrySet()) {
            Path path = root.resolve(entry.getKey()).normalize();
            assertThat(path.startsWith(root)).describedAs("Archive path remains inside the test export").isTrue();
            if (entry.getKey().endsWith("/")) { Files.createDirectories(path); continue; }
            Files.createDirectories(path.getParent()); Files.write(path, entry.getValue());
        }
        return root;
    }

    private static String commit(Git git, String body, String message) throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve("architecture.taxdsl"), body);
        git.add().addFilepattern("architecture.taxdsl").call();
        return git.commit().setMessage(message).setAuthor(author()).setCommitter(author()).call().name();
    }
    private static PersonIdent author() {
        return new PersonIdent("Original Author", "original@example.invalid", Instant.parse("2026-09-01T12:00:00Z"), java.time.ZoneOffset.UTC);
    }
    private static Map<String, String> refs(Repository repository) throws Exception {
        var result = new TreeMap<String, String>();
        for (var ref : repository.getRefDatabase().getRefsByPrefix("refs/")) result.put(ref.getName(), ref.getObjectId().name());
        return result;
    }
    private static String git(Path directory, String... arguments) throws Exception {
        var command = new ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        var process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).describedAs(String.join(" ", command) + "\n" + output).isZero();
        return output;
    }
    static final class Contents implements ComponentSink {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        @Override public void checkpoint() { }
        @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
            var bytes = new ByteArrayOutputStream(); producer.write(bytes); return keep(path, bytes.toByteArray());
        }
        @Override public BackupEntry write(String path, InputStream input) throws IOException { return keep(path, input.readAllBytes()); }
        private BackupEntry keep(String path, byte[] bytes) throws IOException {
            if (entries.putIfAbsent(path, bytes) != null) throw new IOException("Duplicate test entry");
            try { return new BackupEntry(path, bytes.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }
}
