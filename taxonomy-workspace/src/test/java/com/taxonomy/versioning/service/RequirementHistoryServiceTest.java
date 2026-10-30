package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.dsl.requirements.RequirementRealization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;
import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.versioning.service.RequirementHistoryService.ChangeKind.*;
import static com.taxonomy.versioning.service.RequirementHistoryService.Coverage.*;
import static org.mockito.Mockito.*;

class RequirementHistoryServiceTest {
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace("architecture", "alice", "main", "alice");

    @Test void readsActualMessagesParentsAndChangedRequirementVersionsFromGit() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            String first = git.commitDsl("main", requirement("P", "R", "id", "first", 1), "alice", "First request");
            String second = git.commitDsl("main", requirement("P", "R", "id", "second", 2), "bob", "Changed request\n\nReview rationale");
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, null, 0, 50, 500));
            assertThat(page.head()).isEqualTo(second);
            assertThat(page.source()).isEqualTo("LIVE_GIT");
            assertThat(page.coverage()).isEqualTo(COMPLETE);
            assertThat(page.entries()).extracting(RequirementHistoryService.Entry::commitId).containsExactly(second, first);
            assertThat(page.entries().getFirst().parents()).containsExactly(first);
            assertThat(page.entries().getFirst().message()).isEqualTo("Changed request\n\nReview rationale");
            assertThat(page.entries().getFirst().author()).isEqualTo("bob");
            assertThat(page.entries().getFirst().changes().getFirst().kinds()).contains(REQUIREMENT_CHANGED);
            assertThat(page.entries().getFirst().changes().getFirst().beforeVersion()).isEqualTo("1");
            assertThat(page.entries().getFirst().changes().getFirst().afterVersion()).isEqualTo("2");
        }
    }

    @Test void exactBusinessIdentityExcludesOtherProjectsBranchesAndWorkspaces() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            String first = git.commitDsl("main", requirement("P", "R", "custom", "own", 1), "alice", "Own");
            git.commitDsl("main", requirement("P", "R", "custom", "own", 1)
                    + requirement("P-other", "R", "other", "foreign", 1), "bob", "Other project");
            git.commitDsl("unrelated", requirement("P", "R", "custom", "branch", 3), "bob", "Other branch");
            repositories.resolveRepository(RepositoryContext.workspace("architecture", "bob", "main", "bob"))
                    .commitDsl("main", requirement("P", "R", "custom", "workspace", 7), "bob", "Other workspace");
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, null, 0, 50, 500));
            assertThat(page.entries()).extracting(RequirementHistoryService.Entry::commitId).containsExactly(first);
        }
    }

    @Test void boundedScanningReturnsContinuationEvenWhenTheFirstWindowHasNoMatches() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            String first = git.commitDsl("main", requirement("P", "R", "id", "first", 1), "alice", "Needle");
            String head = git.commitDsl("main", requirement("P", "R", "id", "first", 1) + "# unrelated\n", "bob", "Unrelated");
            var service = new RequirementHistoryService(repositories);
            var firstPage = service.search(CONTEXT, query("P", "R", "Needle", null, 0, 50, 1));
            assertThat(firstPage.entries()).isEmpty();
            assertThat(firstPage.coverage()).isEqualTo(PARTIAL);
            assertThat(firstPage.nextOffset()).isEqualTo(1);
            assertThat(firstPage.scanned()).isEqualTo(1);
            var secondPage = service.search(CONTEXT, query("P", "R", "Needle", head, 1, 50, 1));
            assertThat(secondPage.entries()).extracting(RequirementHistoryService.Entry::commitId).containsExactly(first);
            assertThat(secondPage.coverage()).isEqualTo(COMPLETE);
            assertThat(secondPage.nextOffset()).isNull();
        }
    }

    @Test void changedHeadRejectsContinuationInsteadOfMixingSnapshots() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            String first = git.commitDsl("main", requirement("P", "R", "id", "first", 1), "alice", "First");
            git.commitDsl("main", requirement("P", "R", "id", "second", 2), "alice", "Second");
            assertThatThrownBy(() -> new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, first, 1, 20, 500)))
                    .isInstanceOf(BranchHeadConflictException.class);
        }
    }

    @Test void traversesBothExactParentHistoriesAfterPublication() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var destination = repositories.resolveRepository(CONTEXT);
            var source = repositories.openWorkspaceRepository("author");
            String central = destination.commitDsl("main", "# base", "alice", "Central base");
            String original = source.commitDsl("main", requirement("P", "R", "id", "first", 1), "bob", "Original author request");
            GitHistoryTransfer.copyCommitClosure(source, destination, original, List.of(central));
            String published = destination.commitMergeDslIfHeadMatches("main", central, original,
                    requirement("P", "R", "id", "first", 1), "alice", "Publish request", null);
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, null, 0, 50, 500));
            assertThat(page.entries()).extracting(RequirementHistoryService.Entry::commitId).contains(published, original);
            assertThat(page.entries().getFirst().parents()).containsExactly(central, original);
            assertThat(page.entries().getFirst().changes().getFirst().kinds()).contains(MERGE, ADDED);
        }
    }

    @Test void emptyBranchIsComplete() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query(null, null, null, null, 0, 50, 500));
            assertThat(page.head()).isNull();
            assertThat(page.entries()).isEmpty();
            assertThat(page.coverage()).isEqualTo(COMPLETE);
            assertThat(page.nextOffset()).isNull();
        }
    }

    @Test void reviewedDependencyEditsAreVisibleEvenWhenTheRequirementBlockDidNotChange() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            String document = requirement("P", "R", "id", "first", 1)
                    .replace("  text:", "  x-content-hash: \"" + RequirementRealization.textHash("first") + "\";\n  text:")
                    + "element BP type Capability {\n  title: \"Original dependency\";\n}\n";
            document = RequirementRealization.record(document, "id", new RequirementRealization.Review(1,
                    RequirementRealization.textHash("first"), "COMPLETE", "P", "alice", Instant.EPOCH,
                    "Reviewed support", List.of("element:BP")));
            git.commitDsl("main", document, "alice", "Reviewed request");
            String dependencyChange = git.commitDsl("main", document.replace("Original dependency", "Changed support"), "bob", "Dependency edit");
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", "Changed support", null, 0, 50, 500));
            assertThat(page.entries()).extracting(RequirementHistoryService.Entry::commitId).containsExactly(dependencyChange);
            var change = page.entries().getFirst().changes().getFirst();
            assertThat(change.kinds()).containsExactly(DEPENDENCIES_CHANGED);
            assertThat(change.beforeStatus()).isEqualTo("COMPLETE");
            assertThat(change.afterStatus()).isEqualTo("REVIEW_REQUIRED");
        }
    }

    @Test void statusOnlyMetadataAndRemovalAreReportedAsChanges() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            String original = requirement("P", "R", "id", "first", 1);
            git.commitDsl("main", original, "alice", "Initial request");
            String reviewed = git.commitDsl("main", original.replace("  text:", "  x-review-status: \"ACCEPTED\";\n  text:"), "alice", "Review metadata");
            String removed = git.commitDsl("main", "# removed\n", "alice", "Remove request");
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, null, 0, 50, 500));
            assertThat(page.entries().get(0).commitId()).isEqualTo(removed);
            assertThat(page.entries().get(0).changes().getFirst().kinds()).containsExactly(REMOVED);
            assertThat(page.entries().get(1).commitId()).isEqualTo(reviewed);
            assertThat(page.entries().get(1).changes().getFirst().kinds()).containsExactly(REQUIREMENT_METADATA_CHANGED);
        }
    }

    @Test void storageFailureIsNotReportedAsAnEmptySuccessfulSearch() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var real = repositories.resolveRepository(CONTEXT);
            String head = real.commitDsl("main", requirement("P", "R", "id", "first", 1), "alice", "Initial");
            var unavailable = spy(real);
            doThrow(new IOException("storage unavailable")).when(unavailable).getDslAtCommit(head, 16 * 1024 * 1024);
            var routing = mock(DslGitRepositoryFactory.class);
            when(routing.resolveRepository(CONTEXT)).thenReturn(unavailable);
            assertThatThrownBy(() -> new RequirementHistoryService(routing).search(CONTEXT, query("P", "R", null, null, 0, 50, 500)))
                    .isInstanceOf(IOException.class).hasMessage("storage unavailable");
        }
    }

    @Test void ambiguousHistoricalBusinessIdentityFailsInsteadOfAttributingTheCommit() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            git.commitDsl("main", requirement("P", "R", "id", "first", 1)
                    .replace("  text:", "  x-project-key: \"other\";\n  text:"), "alice", "Ambiguous imported identity");
            assertThatThrownBy(() -> new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, null, 0, 50, 500)))
                    .isInstanceOf(IOException.class).hasMessageContaining("Ambiguous historical requirement identity");
        }
    }

    @Test void changingTheCanonicalIdRetainsBusinessIdentityAndReportsMetadataChange() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var git = repositories.resolveRepository(CONTEXT);
            git.commitDsl("main", requirement("P", "R", "old-id", "first", 1), "alice", "Original canonical id");
            String renamed = git.commitDsl("main", requirement("P", "R", "new-id", "first", 1), "alice", "Canonical identity migration");
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query("P", "R", null, null, 0, 50, 500));
            assertThat(page.entries().getFirst().commitId()).isEqualTo(renamed);
            assertThat(page.entries().getFirst().changes().getFirst().kinds()).containsExactly(REQUIREMENT_METADATA_CHANGED);
            assertThat(page.entries().getFirst().changes().getFirst().requirementId()).isEqualTo("new-id");
        }
    }

    @Test void movementDuringThePageReadIsRejectedAtTheFinalHeadCheck() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var real = repositories.resolveRepository(CONTEXT);
            String head = real.commitDsl("main", requirement("P", "R", "id", "first", 1), "alice", "Initial");
            var racing = spy(real);
            doAnswer(call -> {
                real.commitDsl("main", requirement("P", "R", "id", "later", 2), "bob", "Concurrent edit");
                return call.callRealMethod();
            }).when(racing).getDslAtCommit(head, 16 * 1024 * 1024);
            var routing = mock(DslGitRepositoryFactory.class);
            when(routing.resolveRepository(CONTEXT)).thenReturn(racing);
            assertThatThrownBy(() -> new RequirementHistoryService(routing).search(CONTEXT, query("P", "R", null, null, 0, 50, 500)))
                    .isInstanceOf(BranchHeadConflictException.class);
        }
    }

    @Test void queryRejectsUnboundedWindowsMissingPinsAndAmbiguousBusinessKeys() {
        for (int scan : new int[]{0, 501}) assertThatThrownBy(() -> query(null, null, null, null, 0, 50, scan)).isInstanceOf(IllegalArgumentException.class);
        for (int limit : new int[]{0, 101}) assertThatThrownBy(() -> query(null, null, null, null, 0, limit, 500)).isInstanceOf(IllegalArgumentException.class);
        for (int offset : new int[]{-1, 100001}) assertThatThrownBy(() -> query(null, null, null, "a".repeat(40), offset, 50, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(null, null, null, null, 1, 50, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("P", null, null, null, 0, 50, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(null, "R", null, null, 0, 50, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("P\n", "R", null, null, 0, 50, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("P", "R", "x".repeat(501), null, 0, 50, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query("P", "R", null, "main", 0, 50, 500)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RequirementHistoryService.Query query(String project, String requirement, String text, String head,
                                                          int offset, int limit, int scan) {
        return new RequirementHistoryService.Query(project, requirement, text, head, offset, limit, scan);
    }
    private static String requirement(String project, String requirement, String id, String text, int version) {
        return "requirement " + id + " {\n  x-project-key: \"" + project + "\";\n  x-requirement-key: \"" + requirement
                + "\";\n  x-version: \"" + version + "\";\n  text: \"" + text + "\";\n}\n";
    }
}
