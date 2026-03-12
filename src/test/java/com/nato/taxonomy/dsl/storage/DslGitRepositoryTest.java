package com.nato.taxonomy.dsl.storage;

import com.nato.taxonomy.dsl.diff.ModelDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DslGitRepository} — the JGit-based DSL storage layer.
 *
 * <p>Tests use an in-memory DFS repository; no Spring context or database required.
 */
class DslGitRepositoryTest {

    private DslGitRepository repo;

    private static final String SAMPLE_DSL = """
            meta
              language "taxdsl"
              version "1.0"
              namespace "test"

            element CP-1001 type Capability
              title "Secure Voice"

            element CR-2001 type CoreService
              title "Voice Core"

            relation CP-1001 REALIZES CR-2001
              status accepted
            """;

    private static final String SAMPLE_DSL_V2 = """
            meta
              language "taxdsl"
              version "1.0"
              namespace "test"

            element CP-1001 type Capability
              title "Secure Voice"

            element CR-2001 type CoreService
              title "Voice Core"

            element BP-3001 type BuildingBlock
              title "SIP Gateway"

            relation CP-1001 REALIZES CR-2001
              status accepted

            relation CR-2001 REALIZES BP-3001
              status provisional
            """;

    @BeforeEach
    void setUp() {
        repo = new DslGitRepository();
    }

    // ── Commit & read ─────────────────────────────────────────────

    @Test
    void commitAndReadBack() throws IOException {
        String commitId = repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial commit");
        assertNotNull(commitId);
        assertEquals(40, commitId.length(), "Git SHA should be 40 hex chars");

        String readBack = repo.getDslAtCommit(commitId);
        assertEquals(SAMPLE_DSL, readBack);
    }

    @Test
    void readFromHead() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "commit 1");
        repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "commit 2");

        // HEAD should be the latest commit
        String headDsl = repo.getDslAtHead("draft");
        assertEquals(SAMPLE_DSL_V2, headDsl);
    }

    @Test
    void headReturnsNullForEmptyBranch() throws IOException {
        assertNull(repo.getDslAtHead("nonexistent"));
    }

    // ── History ─────────────────────────────────────────────────────

    @Test
    void commitHistoryNewestFirst() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        repo.commitDsl("draft", SAMPLE_DSL_V2, "bob", "second");

        List<DslCommit> history = repo.getDslHistory("draft");
        assertEquals(2, history.size());
        assertEquals("second", history.get(0).message());
        assertEquals("first", history.get(1).message());
    }

    @Test
    void emptyBranchHistoryIsEmptyList() throws IOException {
        List<DslCommit> history = repo.getDslHistory("nonexistent");
        assertTrue(history.isEmpty());
    }

    @Test
    void commitHasAuthorAndTimestamp() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "alice@test.com", "test commit");

        List<DslCommit> history = repo.getDslHistory("draft");
        assertEquals(1, history.size());

        DslCommit commit = history.get(0);
        assertEquals("alice@test.com", commit.author());
        assertEquals("test commit", commit.message());
        assertNotNull(commit.timestamp());
        assertNotNull(commit.commitId());
    }

    // ── Branches ────────────────────────────────────────────────────

    @Test
    void listBranchesInitiallyEmpty() throws IOException {
        assertTrue(repo.listBranches().isEmpty());
    }

    @Test
    void createBranchForks() throws IOException {
        String commitId = repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        String forkedId = repo.createBranch("review", "draft");

        assertEquals(commitId, forkedId);

        List<DslBranch> branches = repo.listBranches();
        assertEquals(2, branches.size());

        // Both branches should have the same DSL
        assertEquals(SAMPLE_DSL, repo.getDslAtHead("draft"));
        assertEquals(SAMPLE_DSL, repo.getDslAtHead("review"));
    }

    @Test
    void createBranchFromEmptyReturnsNull() throws IOException {
        assertNull(repo.createBranch("review", "nonexistent"));
    }

    @Test
    void branchesAreIndependent() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        repo.createBranch("review", "draft");

        // Commit to draft only
        repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2 on draft");

        // draft has v2, review still has v1
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("draft"));
        assertEquals(SAMPLE_DSL, repo.getDslAtHead("review"));
    }

    // ── Diff ────────────────────────────────────────────────────────

    @Test
    void diffBetweenCommits() throws IOException {
        String c1 = repo.commitDsl("draft", SAMPLE_DSL, "tester", "v1");
        String c2 = repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        ModelDiff diff = repo.diffBetween(c1, c2);
        assertFalse(diff.isEmpty());
        // V2 adds BP-3001 element and CR-2001->BP-3001 relation
        assertEquals(1, diff.addedElements().size());
        assertEquals(1, diff.addedRelations().size());
        assertEquals("BP-3001", diff.addedElements().get(0).getId());
    }

    @Test
    void diffIdenticalCommitsIsEmpty() throws IOException {
        String c1 = repo.commitDsl("draft", SAMPLE_DSL, "tester", "same");
        String c2 = repo.commitDsl("review", SAMPLE_DSL, "tester", "same copy");

        ModelDiff diff = repo.diffBetween(c1, c2);
        assertTrue(diff.isEmpty());
    }

    @Test
    void diffBetweenBranches() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "v1");
        repo.commitDsl("review", SAMPLE_DSL_V2, "tester", "v2");

        ModelDiff diff = repo.diffBranches("draft", "review");
        assertFalse(diff.isEmpty());
        assertEquals(1, diff.addedElements().size());
    }

    // ── Multiple commits build proper chain ─────────────────────────

    @Test
    void multipleCommitsFormChain() throws IOException {
        repo.commitDsl("draft", "meta\n  language \"taxdsl\"\n  version \"1.0\"\n  namespace \"x\"\n", "a", "c1");
        repo.commitDsl("draft", SAMPLE_DSL, "b", "c2");
        repo.commitDsl("draft", SAMPLE_DSL_V2, "c", "c3");

        List<DslCommit> history = repo.getDslHistory("draft");
        assertEquals(3, history.size());
        assertEquals("c3", history.get(0).message());
        assertEquals("c2", history.get(1).message());
        assertEquals("c1", history.get(2).message());
    }

    // ── Draft → Review → Accepted workflow ──────────────────────────

    @Test
    void draftReviewAcceptedWorkflow() throws IOException {
        // 1. Auto-analysis commits to draft
        repo.commitDsl("draft", SAMPLE_DSL, "hypothesis-service", "auto-analysis");

        // 2. User reviews and forks to review
        repo.createBranch("review", "draft");

        // 3. User makes changes on review
        repo.commitDsl("review", SAMPLE_DSL_V2, "user@example.com", "refined architecture");

        // 4. Accepted
        repo.createBranch("accepted", "review");

        // Verify each branch has the right content
        assertEquals(SAMPLE_DSL, repo.getDslAtHead("draft"));
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("review"));
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("accepted"));

        // Verify history
        assertEquals(1, repo.getDslHistory("draft").size());
        assertEquals(2, repo.getDslHistory("review").size());  // forked commit + refinement
    }
}
