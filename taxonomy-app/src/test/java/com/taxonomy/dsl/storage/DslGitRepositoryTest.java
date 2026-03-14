package com.taxonomy.dsl.storage;

import com.taxonomy.dsl.diff.ModelDiff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.eclipse.jgit.diff.DiffEntry;

/**
 * Unit tests for {@link DslGitRepository} — the JGit-based DSL storage layer.
 *
 * <p>Tests use an in-memory DFS repository; no Spring context or database required.
 */
class DslGitRepositoryTest {

    private DslGitRepository repo;

    private static final String SAMPLE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }

            element CR-1047 type CoreService {
              title: "Voice Core";
            }

            relation CP-1023 REALIZES CR-1047 {
              status: accepted;
            }
            """;

    private static final String SAMPLE_DSL_V2 = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }

            element CR-1047 type CoreService {
              title: "Voice Core";
            }

            element BP-1481 type BuildingBlock {
              title: "SIP Gateway";
            }

            relation CP-1023 REALIZES CR-1047 {
              status: accepted;
            }

            relation CR-1047 REALIZES BP-1481 {
              status: provisional;
            }
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
        // V2 adds BP-1481 element and CR-1047->BP-1481 relation
        assertEquals(1, diff.addedElements().size());
        assertEquals(1, diff.addedRelations().size());
        assertEquals("BP-1481", diff.addedElements().get(0).getId());
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
        repo.commitDsl("draft", "meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"x\";\n}\n", "a", "c1");
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

    // ── JGit-native text diff ───────────────────────────────────────

    @Test
    void textDiffProducesUnifiedPatch() throws IOException {
        String c1 = repo.commitDsl("draft", SAMPLE_DSL, "tester", "v1");
        String c2 = repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        String textDiff = repo.textDiff(c1, c2);
        assertNotNull(textDiff);
        assertFalse(textDiff.isEmpty());
        // Should contain diff markers
        assertTrue(textDiff.contains("---") || textDiff.contains("+++") || textDiff.contains("@@"),
                "Text diff should contain unified diff markers");
    }

    @Test
    void textDiffIdenticalCommitsIsEmpty() throws IOException {
        String c1 = repo.commitDsl("draft", SAMPLE_DSL, "tester", "same");
        // Same content, different branch — identical tree
        String c2 = repo.commitDsl("review", SAMPLE_DSL, "tester", "same copy");

        String textDiff = repo.textDiff(c1, c2);
        assertNotNull(textDiff);
        assertTrue(textDiff.isEmpty(), "Same content should produce empty diff");
    }

    @Test
    void jgitDiffEntriesDetectsModification() throws IOException {
        String c1 = repo.commitDsl("draft", SAMPLE_DSL, "tester", "v1");
        String c2 = repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        var entries = repo.jgitDiffEntries(c1, c2);
        assertNotNull(entries);
        assertEquals(1, entries.size(), "Only one file (architecture.taxdsl) should be modified");
        assertEquals("architecture.taxdsl", entries.get(0).getNewPath());
    }

    // ── Cherry-pick ─────────────────────────────────────────────────

    @Test
    void cherryPickAppliesCommitToTargetBranch() throws IOException {
        // Setup: draft has v1
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Create review branch from draft
        repo.createBranch("review", "draft");

        // Add v2 to draft
        String v2CommitId = repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "add BP-1481");

        // Cherry-pick the v2 commit onto review
        String newCommitId = repo.cherryPick(v2CommitId, "review");
        assertNotNull(newCommitId, "Cherry-pick should succeed");
        assertNotEquals(v2CommitId, newCommitId, "Cherry-pick creates a new commit");

        // Review branch should now have the v2 content
        String reviewDsl = repo.getDslAtHead("review");
        assertEquals(SAMPLE_DSL_V2, reviewDsl);
    }

    @Test
    void cherryPickToNonexistentBranchReturnsNull() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        String c2 = repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        String result = repo.cherryPick(c2, "nonexistent");
        assertNull(result, "Cherry-pick to non-existent branch should return null");
    }

    // ── Merge ───────────────────────────────────────────────────────

    @Test
    void mergeBranchFastForward() throws IOException {
        // Setup: draft has two commits
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        repo.createBranch("accepted", "draft");

        // Add a commit to draft
        repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "add BP-1481");

        // Merge draft into accepted (should fast-forward)
        String mergeId = repo.merge("draft", "accepted");
        assertNotNull(mergeId, "Merge should succeed");

        // accepted should now have the same content as draft
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("accepted"));
    }

    @Test
    void mergeNonexistentBranchReturnsNull() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        String result = repo.merge("nonexistent", "draft");
        assertNull(result, "Merge from non-existent branch should return null");
    }

    // ── isDatabaseBacked ────────────────────────────────────────────

    @Test
    void inMemoryRepoIsNotDatabaseBacked() {
        assertFalse(repo.isDatabaseBacked(), "InMemory repo should not be database-backed");
    }

    // ── getHeadCommit ───────────────────────────────────────────────

    @Test
    void getHeadCommitReturnsCommitSha() throws IOException {
        String commitId = repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        assertEquals(commitId, repo.getHeadCommit("draft"));
    }

    @Test
    void getHeadCommitReturnsNullForNonexistentBranch() throws IOException {
        assertNull(repo.getHeadCommit("nonexistent"));
    }

    @Test
    void getHeadCommitReturnsLatestAfterMultipleCommits() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "first");
        String second = repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "second");
        assertEquals(second, repo.getHeadCommit("draft"));
    }

    // ── getBranchNames ──────────────────────────────────────────────

    @Test
    void getBranchNamesInitiallyEmpty() throws IOException {
        assertTrue(repo.getBranchNames().isEmpty());
    }

    @Test
    void getBranchNamesReturnsBranchNames() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        repo.createBranch("review", "draft");

        var names = repo.getBranchNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("draft"));
        assertTrue(names.contains("review"));
    }

    // ── getCommitCount ──────────────────────────────────────────────

    @Test
    void getCommitCountReturnsZeroForNonexistentBranch() throws IOException {
        assertEquals(0, repo.getCommitCount("nonexistent"));
    }

    @Test
    void getCommitCountCountsAllCommits() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "first");
        repo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "second");
        repo.commitDsl("draft", SAMPLE_DSL, "tester", "third");
        assertEquals(3, repo.getCommitCount("draft"));
    }

    // ── getHeadCommitInfo ───────────────────────────────────────────

    @Test
    void getHeadCommitInfoReturnsNullForNonexistentBranch() throws IOException {
        assertNull(repo.getHeadCommitInfo("nonexistent"));
    }

    @Test
    void getHeadCommitInfoReturnsCommitMetadata() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "alice@test.com", "initial commit");

        DslCommit info = repo.getHeadCommitInfo("draft");
        assertNotNull(info);
        assertEquals("alice@test.com", info.author());
        assertEquals("initial commit", info.message());
        assertNotNull(info.commitId());
        assertNotNull(info.timestamp());
    }

    @Test
    void getHeadCommitInfoReturnsLatestCommit() throws IOException {
        repo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        repo.commitDsl("draft", SAMPLE_DSL_V2, "bob", "second");

        DslCommit info = repo.getHeadCommitInfo("draft");
        assertNotNull(info);
        assertEquals("bob", info.author());
        assertEquals("second", info.message());
    }

    // ── Full draft → cherry-pick → review → merge → accepted ────────

    @Test
    void fullGitWorkflowWithCherryPickAndMerge() throws IOException {
        // 1. Auto-analysis commits to draft
        repo.commitDsl("draft", SAMPLE_DSL, "hypothesis-service", "auto-analysis");

        // 2. Create review and accepted branches
        repo.createBranch("review", "draft");
        repo.createBranch("accepted", "draft");

        // 3. New analysis result on draft
        String draftV2 = repo.commitDsl("draft", SAMPLE_DSL_V2, "hypothesis-service", "refined");

        // 4. Cherry-pick the refined commit to review
        String reviewCommit = repo.cherryPick(draftV2, "review");
        assertNotNull(reviewCommit);

        // 5. Merge review into accepted
        String acceptedMerge = repo.merge("review", "accepted");
        assertNotNull(acceptedMerge);

        // Verify final state
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("draft"));
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("review"));
        assertEquals(SAMPLE_DSL_V2, repo.getDslAtHead("accepted"));

        // Verify history lengths
        assertEquals(2, repo.getDslHistory("draft").size());
        assertTrue(repo.getDslHistory("review").size() >= 2);
        assertTrue(repo.getDslHistory("accepted").size() >= 2);
    }
}
