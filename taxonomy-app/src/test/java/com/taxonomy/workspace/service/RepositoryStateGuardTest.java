package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.taxonomy.versioning.service.RepositoryStateService;

/**
 * Unit tests for {@link RepositoryStateGuard}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 * Tests verify per-user workspace isolation via the WorkspaceManager.
 */
class RepositoryStateGuardTest {

    private DslGitRepository gitRepo;
    private RepositoryStateService stateService;
    private RepositoryStateGuard guard;

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

    private static final String USER = "testuser";

    @BeforeEach
    void setUp() {
        gitRepo = new DslGitRepository();
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        WorkspaceManager workspaceManager = new WorkspaceManager(wsRepo, 50,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class), gitRepo);
        stateService = new RepositoryStateService(gitRepo, workspaceManager);
        guard = new RepositoryStateGuard(stateService, null);
    }

    @Test
    void commitOnEmptyBranchIsAllowed() {
        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertTrue(check.allowed(), "Commit on empty branch should be allowed");
        assertTrue(check.blocks().isEmpty());
    }

    @Test
    void materializeOnNonexistentBranchIsBlocked() {
        var check = guard.checkWriteOperation(USER, "nonexistent", "materialize");
        assertFalse(check.allowed(), "Materialize on nonexistent branch should be blocked");
        assertFalse(check.blocks().isEmpty());
    }

    @Test
    void operationBlockedWhenAnotherInProgress() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        stateService.beginOperation(USER, "merge");

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertFalse(check.allowed(), "Should be blocked when operation in progress");
        assertTrue(check.blocks().stream().anyMatch(b -> b.contains("merge")));
    }

    @Test
    void staleProjectionGeneratesWarning() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "first");
        stateService.recordProjection(USER, commitId, "draft");

        // Move HEAD ahead
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "second");

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertTrue(check.allowed(), "Should be allowed but with warnings");
        assertTrue(check.warnings().stream().anyMatch(w -> w.contains("stale")));
    }

    @Test
    void staleIndexGeneratesWarning() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "first");
        stateService.recordIndexBuild(USER, commitId);

        // Move HEAD ahead
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "second");

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertTrue(check.allowed(), "Should be allowed but with warnings");
        assertTrue(check.warnings().stream().anyMatch(w -> w.contains("index")));
    }

    @Test
    void freshStateHasNoWarnings() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        stateService.recordProjection(USER, commitId, "draft");
        stateService.recordIndexBuild(USER, commitId);

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertTrue(check.allowed());
        assertTrue(check.warnings().isEmpty());
        assertTrue(check.blocks().isEmpty());
    }

    @Test
    void operationAllowedAfterEnd() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        stateService.beginOperation(USER, "cherry-pick");
        stateService.endOperation(USER);

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertTrue(check.allowed());
    }

    // ── Extended guard combinations ──────────────────────────────────

    @Test
    void mergeBlockedDuringOperation() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        stateService.beginOperation(USER, "revert");

        var check = guard.checkWriteOperation(USER, "draft", "merge");
        assertFalse(check.allowed());
        assertTrue(check.blocks().stream().anyMatch(b -> b.contains("revert")));
    }

    @Test
    void cherryPickBlockedDuringOperation() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        stateService.beginOperation(USER, "merge");

        var check = guard.checkWriteOperation(USER, "draft", "cherry-pick");
        assertFalse(check.allowed());
    }

    @Test
    void importBlockedDuringOperation() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        stateService.beginOperation(USER, "cherry-pick");

        var check = guard.checkWriteOperation(USER, "draft", "import");
        assertFalse(check.allowed());
    }

    @Test
    void staleProjectionAndStaleIndexBothWarn() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "first");
        stateService.recordProjection(USER, commitId, "draft");
        stateService.recordIndexBuild(USER, commitId);

        // Move HEAD ahead
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "second");

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertTrue(check.allowed());
        // Both projection and index should be stale
        assertTrue(check.warnings().size() >= 2,
                "Both projection and index should generate warnings but got: " + check.warnings());
    }

    @Test
    void operationBlockedEvenWithStaleState() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "first");
        stateService.recordProjection(USER, commitId, "draft");
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "second");

        stateService.beginOperation(USER, "merge");

        var check = guard.checkWriteOperation(USER, "draft", "commit");
        assertFalse(check.allowed(), "Operation in progress should block even if state is stale");
    }
}
