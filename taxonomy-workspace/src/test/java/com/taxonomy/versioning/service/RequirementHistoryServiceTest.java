package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static com.taxonomy.versioning.service.RequirementHistoryService.ChangeKind.*;
import static com.taxonomy.versioning.service.RequirementHistoryService.Coverage.*;

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

    @Test void emptyBranchIsCompleteButUnreadableHistoryFails() throws Exception {
        try (var repositories = new DslGitRepositoryFactory(null)) {
            var page = new RequirementHistoryService(repositories).search(CONTEXT, query(null, null, null, null, 0, 50, 500));
            assertThat(page.head()).isNull();
            assertThat(page.entries()).isEmpty();
            assertThat(page.coverage()).isEqualTo(COMPLETE);
            assertThat(page.nextOffset()).isNull();
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
