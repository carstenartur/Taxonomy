package com.taxonomy.workspace.storage;

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RebaseDslConflictTest {
    private static final String MODEL = DslGitRepository.DSL_FILENAME;
    private static final int LIMIT = RebaseDslConflict.MAX_TEXT_BYTES;
    private static final String TARGET_ID = "b".repeat(40);

    @Test
    void conflictPinsEachSidesExactBlobAndPreservesUtf8AtTheLimit() throws IOException {
        try (var repository = new InMemoryRepository(new DfsRepositoryDescription("review"))) {
            String baseText = "shared\r\n";
            String targetText = "ö".repeat(LIMIT / 2);
            String sourceText = "incoming\n";
            var base = commit(repository, MODEL, FileMode.REGULAR_FILE, utf8(baseText));
            var target = commit(repository, MODEL, FileMode.REGULAR_FILE, utf8(targetText));
            var source = commit(repository, MODEL, FileMode.REGULAR_FILE, utf8(sourceText));

            var conflict = RebaseDslConflict.read(repository, base, target, source);

            assertThat(conflict).isNotNull();
            assertVersion(repository, conflict.base(), baseText);
            assertVersion(repository, conflict.target(), targetText);
            assertVersion(repository, conflict.source(), sourceText);
        }
    }

    @Test
    void aMissingBaseSymlinkTargetOrUnreadableSourceOffersNoTextResolution() throws IOException {
        try (var repository = new InMemoryRepository(new DfsRepositoryDescription("unsupported"))) {
            var regular = commit(repository, MODEL, FileMode.REGULAR_FILE, utf8("model"));
            var missing = commit(repository, "notes.txt", FileMode.REGULAR_FILE, utf8("model"));
            var link = commit(repository, MODEL, FileMode.SYMLINK, utf8("other.dsl"));
            var malformed = commit(repository, MODEL, FileMode.REGULAR_FILE, new byte[]{(byte) 0xc3, 0x28});
            var oversized = commit(repository, MODEL, FileMode.REGULAR_FILE, utf8("x".repeat(LIMIT + 1)));

            assertThat(RebaseDslConflict.read(repository, missing, regular, regular)).isNull();
            assertThat(RebaseDslConflict.read(repository, regular, link, regular)).isNull();
            assertThat(RebaseDslConflict.read(repository, regular, regular, malformed)).isNull();
            assertThat(RebaseDslConflict.read(repository, regular, regular, oversized)).isNull();
        }
    }

    @Test
    void aResolutionRequiresAReviewedBlobAndBoundedNonBinaryText() {
        assertInvalidResolution(null, "chosen");
        assertInvalidResolution("review-branch", "chosen");
        assertInvalidResolution(TARGET_ID, null);
        assertInvalidResolution(TARGET_ID, "before\0after");
        assertInvalidResolution(TARGET_ID, "x".repeat(LIMIT + 1));
        assertInvalidResolution(TARGET_ID, "ö".repeat(LIMIT / 2) + "x");

        assertThat(new RebaseDslConflict.Resolution(TARGET_ID, "").text()).isEmpty();
        var text = "ö".repeat(LIMIT / 2);
        var reviewed = new RebaseDslConflict.Resolution(TARGET_ID, text);
        assertThat(reviewed.targetBlobId()).isEqualTo(TARGET_ID);
        assertThat(reviewed.text()).isEqualTo(text);
    }

    private static void assertInvalidResolution(String blobId, String text) {
        assertThrows(IllegalArgumentException.class, () -> new RebaseDslConflict.Resolution(blobId, text));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertVersion(Repository repository, RebaseDslConflict.Version version, String text)
            throws IOException {
        assertThat(version.text()).isEqualTo(text);
        var blob = repository.open(ObjectId.fromString(version.blobId()), Constants.OBJ_BLOB);
        assertThat(blob.getBytes()).isEqualTo(utf8(text));
    }

    private static RevCommit commit(Repository repository, String filename, FileMode mode, byte[] bytes)
            throws IOException {
        try (var inserter = repository.newObjectInserter(); var walk = new RevWalk(repository)) {
            var tree = new TreeFormatter();
            tree.append(filename, mode, inserter.insert(Constants.OBJ_BLOB, bytes));
            var commit = new CommitBuilder();
            var author = new PersonIdent("Reviewer", "reviewer@example.org");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setTreeId(inserter.insert(tree));
            commit.setMessage("Retained version");
            var id = inserter.insert(commit);
            inserter.flush();
            return walk.parseCommit(id);
        }
    }
}
