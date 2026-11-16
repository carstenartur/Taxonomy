package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;


/**
 * Unit tests for {@link ConflictDetectionService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 */
class ConflictDetectionServiceTest {

    private DslGitRepositoryFactory factory;
    private DslGitRepository gitRepo;
    private ConflictDetectionService conflictService;

    private static final String SAMPLE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
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

            element BP-1481 type BuildingBlock {
              title: "SIP Gateway";
            }
            """;

    private static final String CONFLICT_LEFT = SAMPLE_DSL.replace(
            "Secure Voice", "Secure Voice from source");
    private static final String CONFLICT_RIGHT = SAMPLE_DSL.replace(
            "Secure Voice", "Secure Voice from target");

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        conflictService = new ConflictDetectionService(factory);
    }

    // ── Merge preview ───────────────────────────────────────────────

    @Test
    void previewMergeMissingSourceBranch() {
        var result = conflictService.previewMerge("nonexistent", "draft");
        assertFalse(result.canMerge());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not found")));
    }

    @Test
    void previewMergeMissingTargetBranch() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        var result = conflictService.previewMerge("draft", "nonexistent");
        assertFalse(result.canMerge());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not found")));
    }

    @Test
    void previewMergeAlreadyMerged() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("review", "draft");

        var result = conflictService.previewMerge("draft", "review");
        assertTrue(result.canMerge());
        assertTrue(result.alreadyMerged());
    }

    @Test
    void previewMergeFastForward() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("accepted", "draft");
        gitRepo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        var result = conflictService.previewMerge("draft", "accepted");
        assertTrue(result.canMerge());
        assertTrue(result.fastForwardable());
        assertFalse(result.alreadyMerged());
    }

    @Test
    void previewMergeThreeWay() throws IOException {
        // Create divergent branches
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("review", "draft");
        gitRepo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2 on draft");
        gitRepo.commitDsl("review", SAMPLE_DSL, "tester", "touch on review");

        var result = conflictService.previewMerge("draft", "review");
        assertTrue(result.canMerge());
        assertFalse(result.fastForwardable());
        assertFalse(result.alreadyMerged());
    }

    // ── Cherry-pick preview ─────────────────────────────────────────

    @Test
    void previewCherryPickMissingTargetBranch() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        String commitId = gitRepo.getHeadCommit("draft");

        var result = conflictService.previewCherryPick(commitId, "nonexistent");
        assertFalse(result.canCherryPick());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("not found")));
    }

    @Test
    void previewCherryPickSuccess() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("review", "draft");
        String v2Commit = gitRepo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        var result = conflictService.previewCherryPick(v2Commit, "review");
        assertTrue(result.canCherryPick());
        assertNotNull(result.targetCommit());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void cherryPickingSecondCommitLeavesTheUnselectedEditOut() throws IOException {
        String base = "original title\nseparator\noriginal owner\n";
        gitRepo.commitDsl("draft", base, "tester", "base");
        gitRepo.createBranch("review", "draft");
        String first = base.replace("original title", "unselected title");
        gitRepo.commitDsl("draft", first, "tester", "unselected edit");
        String picked = gitRepo.commitDsl("draft", first.replace("original owner", "picked owner"), "tester", "owner");

        assertTrue(conflictService.previewCherryPick(picked, "review").canCherryPick());
        String result = gitRepo.cherryPick(picked, "review");
        assertNotNull(result);
        assertEquals("original title\nseparator\npicked owner\n", gitRepo.getDslAtCommit(result));
    }

    @Test
    void previewAndPickIgnoreAnEarlierConflictOnAnUnchangedLine() throws IOException {
        String base = "original title\nseparator\noriginal owner\n";
        gitRepo.commitDsl("draft", base, "tester", "base");
        gitRepo.createBranch("review", "draft");
        String first = base.replace("original title", "unselected title");
        gitRepo.commitDsl("draft", first, "tester", "unselected edit");
        String picked = gitRepo.commitDsl("draft", first.replace("original owner", "picked owner"), "tester", "owner");
        gitRepo.commitDsl("review", base.replace("original title", "our title"), "tester", "our edit");

        assertTrue(conflictService.previewCherryPick(picked, "review").canCherryPick());
        assertNotNull(gitRepo.cherryPick(picked, "review"));
        assertEquals("our title\nseparator\npicked owner\n", gitRepo.getDslAtHead("review"));
    }

    @Test
    void pickingAnAncestorRootUsesTheEmptyTreeAsItsBase() throws IOException {
        String root = gitRepo.commitDsl("draft", "root text", "tester", "initial architecture");
        String target = gitRepo.commitDsl("draft", "changed text", "tester", "local edit");

        assertFalse(conflictService.previewCherryPick(root, "draft").canCherryPick());
        assertNull(gitRepo.cherryPick(root, "draft"));
        assertEquals(target, gitRepo.getHeadCommit("draft"));
    }

    @Test
    void mergeCommitNeedsAnExplicitMainlineBeforeItCanBePicked() throws IOException {
        String base = gitRepo.commitDsl("draft", "base", "tester", "initial");
        gitRepo.createBranch("review", "draft");
        String side = gitRepo.commitDsl("review", "side", "tester", "review");
        String ours = gitRepo.commitDsl("draft", "ours", "tester", "our edit");
        String merge = gitRepo.commitMergeDslIfHeadMatches("draft", ours, side, "merged", "tester", "merge", null);
        gitRepo.createBranchAtCommit("target", base);

        assertFalse(conflictService.previewCherryPick(merge, "target").canCherryPick());
        assertTrue(conflictService.previewCherryPick(merge, "target").warnings().stream()
                .anyMatch(warning -> warning.contains("mainline")));
        assertNull(conflictService.getCherryPickConflictDetails(merge, "target"));
        assertThrows(IllegalArgumentException.class, () -> gitRepo.cherryPick(merge, "target"));
        assertEquals(base, gitRepo.getHeadCommit("target"));
    }

    @Test
    void previewCherryPickInvalidCommit() {
        var result = conflictService.previewCherryPick("0000000000000000000000000000000000000000", "draft");
        assertFalse(result.canCherryPick());
        assertFalse(result.warnings().isEmpty());
    }

    // ── Conflict details ────────────────────────────────────────────

    @Test
    void getMergeConflictDetails_noConflict() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("review", "draft");
        gitRepo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        var details = conflictService.getMergeConflictDetails("draft", "review");
        // Fast-forward case — no conflict
        assertNull(details);
    }

    @Test
    void getMergeConflictDetails_branchNotFound() {
        // When source branch doesn't exist, preview returns canMerge=false
        // with fromCommit=null. This is not a real conflict, so null is returned.
        var details = conflictService.getMergeConflictDetails("nonexistent", "draft");
        assertNull(details);
    }

    @Test
    void getCherryPickConflictDetails_noConflict() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("review", "draft");
        String v2Commit = gitRepo.commitDsl("draft", SAMPLE_DSL_V2, "tester", "v2");

        var details = conflictService.getCherryPickConflictDetails(v2Commit, "review");
        // Clean cherry-pick — no conflict
        assertNull(details);
    }

    @Test
    void getCherryPickConflictDetails_invalidCommit() {
        var details = conflictService.getCherryPickConflictDetails(
                "0000000000000000000000000000000000000000", "draft");
        // Invalid commit → null
        assertNull(details);
    }

    @Test
    void mergeConflictPreviewAndDetailsExposeBothBranchContents() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "base");
        gitRepo.createBranch("review", "draft");
        gitRepo.commitDsl("draft", CONFLICT_LEFT, "tester", "source edit");
        gitRepo.commitDsl("review", CONFLICT_RIGHT, "tester", "target edit");

        var preview = conflictService.previewMerge("draft", "review");
        var details = conflictService.getMergeConflictDetails("draft", "review");

        assertFalse(preview.canMerge());
        assertNotNull(preview.fromCommit());
        assertNotNull(preview.intoCommit());
        assertTrue(preview.warnings().contains("Merge would result in conflicts"));
        assertNotNull(details);
        assertEquals("merge", details.conflictType());
        assertEquals(CONFLICT_RIGHT, details.oursContent());
        assertEquals(CONFLICT_LEFT, details.theirsContent());
    }

    @Test
    void cherryPickDetailsReadTheCommitReviewedEvenIfTheBranchMoves() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "base");
        gitRepo.createBranch("review", "draft");
        String picked = gitRepo.commitDsl("draft", CONFLICT_LEFT, "tester", "source edit");
        String reviewed = gitRepo.commitDsl("review", CONFLICT_RIGHT, "tester", "reviewed target");
        var service = new ConflictDetectionService(factory) {
            @Override
            public CherryPickPreview previewCherryPick(String commitId, String branch, WorkspaceContext context) {
                var preview = super.previewCherryPick(commitId, branch, context);
                assertDoesNotThrow(() -> gitRepo.commitDsl(branch, "newer edit", "other", "concurrent edit"));
                return preview;
            }
        };

        var details = service.getCherryPickConflictDetails(picked, "review");
        assertNotNull(details);
        assertEquals(reviewed, details.targetCommit());
        assertEquals(CONFLICT_RIGHT, details.oursContent());
        assertEquals("newer edit", gitRepo.getDslAtHead("review"));
    }

    @Test
    void cherryPickConflictPreviewAndDetailsExposeTargetAndPickedCommit() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "base");
        gitRepo.createBranch("review", "draft");
        String picked = gitRepo.commitDsl("draft", CONFLICT_LEFT, "tester", "source edit");
        gitRepo.commitDsl("review", CONFLICT_RIGHT, "tester", "target edit");

        var preview = conflictService.previewCherryPick(picked, "review");
        var details = conflictService.getCherryPickConflictDetails(picked, "review");

        assertFalse(preview.canCherryPick());
        assertNotNull(preview.targetCommit());
        assertTrue(preview.warnings().contains("Cherry-pick would result in conflicts"));
        assertNotNull(details);
        assertEquals("cherry-pick", details.conflictType());
        assertEquals(preview.targetCommit(), details.targetCommit());
        assertEquals(SAMPLE_DSL, details.baseContent());
        assertEquals(CONFLICT_RIGHT, details.oursContent());
        assertEquals(CONFLICT_LEFT, details.theirsContent());
    }
}
