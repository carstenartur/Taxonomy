package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class GitHistoryDocumentsTest {
    private static final BackupCheckpoint CHECK = () -> { };
    private static final BackupRepositoryKey KEY = new BackupRepositoryKey("repository-a", "workspace-a");
    @TempDir Path temporary;

    @Test void documentsIncludeDeletedPathsAncestorsAndDatabaseOnlyCommitsWithOriginalContentHashes() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            write(source, "architecture.taxdsl", "ORIGINAL");
            write(source, "removed/old.taxdsl", "HISTORICAL DOCUMENT");
            write(source, "readme.txt", "NON-DOCUMENT");
            String first = commit(git, "original");
            Files.delete(source.resolve("removed/old.taxdsl"));
            git.rm().addFilepattern("removed/old.taxdsl").call();
            write(source, "architecture.taxdsl", "CURRENT");
            String current = commit(git, "current");
            git.checkout().setCreateBranch(true).setName("discarded").call();
            write(source, "architecture.taxdsl", "DATABASE-ONLY");
            String required = commit(git, "required by durable application state");
            git.checkout().setName("main").call();
            git.branchDelete().setBranchNames("discarded").setForce(true).call();
            // The first commit is only reachable ancestry; the discarded commit is only a DB root.
            Path logs = source.resolve(".git/logs");
            if (Files.exists(logs)) try (var paths = Files.walk(logs)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(required), Map.of(),
                    GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.documents(KEY, CHECK)).containsExactlyInAnyOrder(
                    reference(first, "architecture.taxdsl", "ORIGINAL"),
                    reference(first, "removed/old.taxdsl", "HISTORICAL DOCUMENT"),
                    reference(current, "architecture.taxdsl", "CURRENT"),
                    reference(required, "architecture.taxdsl", "DATABASE-ONLY"));
            assertThat(capture.state().requiredCommits()).containsExactlyInAnyOrder(first, current, required);
        }
    }

    @Test void documentEvidenceHasAnExplicitBoundAcrossCommitVersions() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            write(source, "architecture.taxdsl", "OLD"); commit(git, "old");
            write(source, "architecture.taxdsl", "NEW"); commit(git, "new");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(),
                    GitHistoryCapture.Limits.defaults(), CHECK);
            assertThatThrownBy(() -> capture.documents(KEY, 1, CHECK)).isInstanceOf(IOException.class);
        }
    }

    @Test void documentReaderCopiesAnOriginalDeletedVersionWithAContentProof() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            write(source, "old.taxdsl", "ORIGINAL embedded-secret"); String first = commit(git, "original");
            git.rm().addFilepattern("old.taxdsl").call();
            write(source, "architecture.taxdsl", "CURRENT"); commit(git, "current");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            var output = new ByteArrayOutputStream();
            capture.copyDocument(reference(first, "old.taxdsl", "ORIGINAL embedded-secret"), 100, output, CHECK);
            assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("ORIGINAL embedded-secret");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"checksum", "commit", "path", "non-document", "bound"})
    void documentReaderRejectsUnprovenOrExcessiveContentBeforeWritingBytes(String invalid) throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            write(source, "architecture.taxdsl", "ORIGINAL"); write(source, "secret.txt", "ORIGINAL");
            String commit = commit(git, "original");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            var reference = reference(invalid.equals("commit") ? "f".repeat(40) : commit,
                    invalid.equals("path") ? "absent.taxdsl" : invalid.equals("non-document") ? "secret.txt" : "architecture.taxdsl",
                    invalid.equals("checksum") ? "FORGED" : "ORIGINAL");
            var output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> capture.copyDocument(reference, invalid.equals("bound") ? 2 : 100, output, CHECK)).isInstanceOf(IOException.class);
            assertThat(output.size()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refs", "expired-session", "cancelled"})
    void documentReaderRechecksItsSourceAndSessionBeforeCopying(String change) throws Exception {
        Path source = temporary.resolve("source"); var active = new AtomicBoolean(true);
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            write(source, "architecture.taxdsl", "ORIGINAL"); String first = commit(git, "original");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK,
                    () -> { if (!active.get()) throw new IOException("capture ended"); });
            if (change.equals("refs")) { write(source, "architecture.taxdsl", "MOVED"); commit(git, "moved"); }
            if (change.equals("expired-session")) active.set(false);
            BackupCheckpoint check = change.equals("cancelled") ? () -> { throw new IOException("cancelled"); } : CHECK;
            var output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> capture.copyDocument(reference(first, "architecture.taxdsl", "ORIGINAL"), 100, output, check)).isInstanceOf(IOException.class);
            assertThat(output.size()).isZero();
        }
    }

    @Test void nativeNonDocumentPathsDoNotNeedAUnicodeNameToPreserveTheHistory() throws Exception {
        Path source = temporary.resolve("source");
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            var repository = git.getRepository();
            String current;
            try (var inserter = repository.newObjectInserter()) {
                var tree = new TreeFormatter();
                tree.append("architecture.taxdsl", FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, "DOCUMENT".getBytes(StandardCharsets.UTF_8)));
                tree.append(new byte[]{'b','i','n','a','r','y','-',(byte) 0xff,'.','d','a','t'}, FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, new byte[]{0, 1, 2}));
                var commit = new CommitBuilder();
                commit.setTreeId(inserter.insert(tree));
                var actor = new PersonIdent("Original Author", "original@example.invalid");
                commit.setAuthor(actor); commit.setCommitter(actor); commit.setMessage("native byte path");
                var id = inserter.insert(commit); inserter.flush();
                var ref = repository.updateRef("refs/heads/main");
                ref.setExpectedOldObjectId(org.eclipse.jgit.lib.ObjectId.zeroId());
                ref.setNewObjectId(id); ref.setRefLogIdent(actor); ref.setRefLogMessage("commit: native byte path", false);
                assertThat(ref.update()).isEqualTo(RefUpdate.Result.NEW);
                current = id.name();
            }
            var capture = GitHistoryCapture.capture(repository, Set.of(), Map.of(), GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(capture.documents(KEY, CHECK)).containsExactly(reference(current, "architecture.taxdsl", "DOCUMENT"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"refs", "expired-session", "cancelled"})
    void documentEvidenceCannotOutliveItsCapture(String change) throws Exception {
        Path source = temporary.resolve("source"); var active = new AtomicBoolean(true);
        try (var git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            write(source, "architecture.taxdsl", "ORIGINAL"); commit(git, "original");
            var capture = GitHistoryCapture.capture(git.getRepository(), Set.of(), Map.of(),
                    GitHistoryCapture.Limits.defaults(), CHECK,
                    () -> { if (!active.get()) throw new IOException("capture ended"); });
            if (change.equals("refs")) { write(source, "architecture.taxdsl", "MOVED"); commit(git, "moved"); }
            if (change.equals("expired-session")) active.set(false);
            BackupCheckpoint check = change.equals("cancelled") ? () -> { throw new IOException("cancelled"); } : CHECK;
            assertThatThrownBy(() -> capture.documents(KEY, check)).isInstanceOf(IOException.class);
        }
    }

    private static void write(Path source, String path, String content) throws IOException {
        Files.createDirectories(source.resolve(path).getParent());
        Files.writeString(source.resolve(path), content);
    }
    private static String commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        var author = new PersonIdent("Original Author", "original@example.invalid");
        return git.commit().setAuthor(author).setCommitter(author).setMessage(message).call().name();
    }
    private static BackupDocumentReference reference(String commit, String path, String content) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        return new BackupDocumentReference(KEY, commit, path, hash);
    }
}
