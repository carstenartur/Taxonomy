package com.taxonomy.workspace.storage;

import org.eclipse.jgit.lib.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DslCapturedBranchTest {
    @Test
    void capturedBranchRetainsTheCompleteAncestryWithoutFollowingLaterCommits() throws Exception {
        try (var repository = new DslGitRepository()) {
            String parent = repository.commitDsl("draft", "parent", "author", "Parent");
            String captured = repository.commitDsl("draft", "captured", "author", "Captured");
            String later = repository.commitDsl("draft", "later", "author", "Later");

            assertEquals(captured, repository.createBranchAtCommit("fork", captured));

            assertEquals(captured, repository.getHeadCommit("fork"));
            assertEquals("captured", repository.getDslAtHead("fork"));
            assertEquals(List.of(captured, parent), repository.getDslHistory("fork").stream()
                    .map(DslCommit::commitId).toList());
            assertEquals(later, repository.getHeadCommit("draft"));
        }
    }

    @Test
    void anExistingDestinationCannotBeOverwrittenEvenByAFastForward() throws Exception {
        try (var repository = new DslGitRepository()) {
            String first = repository.commitDsl("draft", "first", "author", "First");
            repository.createBranchAtCommit("fork", first);
            String later = repository.commitDsl("draft", "later", "author", "Later");

            assertThrows(IOException.class, () -> repository.createBranchAtCommit("fork", later));

            assertEquals(first, repository.getHeadCommit("fork"));
            assertEquals("first", repository.getDslAtHead("fork"));
            assertEquals(1, repository.getDslHistory("fork").size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrNonCommitObjectsNeverBecomeBranchHeads(boolean existingBlob) throws Exception {
        try (var repository = new DslGitRepository()) {
            String objectId;
            if (existingBlob) {
                try (var inserter = repository.getGitRepository().newObjectInserter()) {
                    objectId = inserter.insert(Constants.OBJ_BLOB, "not a commit".getBytes(StandardCharsets.UTF_8)).name();
                    inserter.flush();
                }
            } else objectId = "1".repeat(40);

            assertThrows(IOException.class, () -> repository.createBranchAtCommit("fork", objectId));

            assertNull(repository.getHeadCommit("fork"));
            assertTrue(repository.getBranchNames().isEmpty());
        }
    }
}
