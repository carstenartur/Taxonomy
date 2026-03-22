package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.WorkspaceProjection;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.repository.WorkspaceProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WorkspaceProjectionService}.
 *
 * <p>Verifies per-user projection lifecycle: creation, recording,
 * staleness detection, and info retrieval. No Spring context required.
 */
class WorkspaceProjectionServiceTest {

    private DslGitRepository gitRepo;
    private WorkspaceManager workspaceManager;
    private WorkspaceProjectionRepository projectionRepo;
    private WorkspaceProjectionService projectionService;

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

    @BeforeEach
    void setUp() {
        var factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        when(wsRepo.findByUsernameAndSharedFalse(anyString())).thenReturn(Optional.empty());
        workspaceManager = new WorkspaceManager(wsRepo, 50,
                mock(SystemRepositoryService.class), gitRepo);
        projectionRepo = mock(WorkspaceProjectionRepository.class);
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        when(contextResolver.resolveCurrentContext()).thenReturn(WorkspaceContext.SHARED);
        projectionService = new WorkspaceProjectionService(projectionRepo, workspaceManager, factory, wsRepo, contextResolver);
    }

    // ── Projection creation ─────────────────────────────────────────

    @Test
    void getOrCreateProjection_createsNewProjection() {
        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.empty());
        when(projectionRepo.save(any(WorkspaceProjection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkspaceProjection projection = projectionService.getOrCreateProjection("alice");

        assertNotNull(projection);
        assertEquals("alice", projection.getUsername());
        assertNotNull(projection.getWorkspaceId());
        assertFalse(projection.isStale());
        assertNotNull(projection.getCreatedAt());
        verify(projectionRepo).save(any(WorkspaceProjection.class));
    }

    @Test
    void getOrCreateProjection_returnsSameProjection() {
        WorkspaceProjection existing = new WorkspaceProjection();
        existing.setUsername("alice");
        existing.setWorkspaceId("ws-123");

        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.of(existing));

        WorkspaceProjection projection = projectionService.getOrCreateProjection("alice");

        assertSame(existing, projection);
        verify(projectionRepo, never()).save(any());
    }

    // ── Recording ───────────────────────────────────────────────────

    @Test
    void recordProjection_updatesCommitAndTimestamp() {
        WorkspaceProjection existing = new WorkspaceProjection();
        existing.setUsername("alice");
        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.of(existing));
        when(projectionRepo.save(any(WorkspaceProjection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        projectionService.recordProjection("alice", "abc1234", "draft");

        assertEquals("abc1234", existing.getProjectionCommitId());
        assertEquals("draft", existing.getProjectionBranch());
        assertNotNull(existing.getProjectionTimestamp());
        assertFalse(existing.isStale());
        verify(projectionRepo).save(existing);

        // In-memory state should also be updated
        UserWorkspaceState ws = workspaceManager.getOrCreateWorkspace("alice");
        assertEquals("abc1234", ws.getLastProjectionCommit());
        assertEquals("draft", ws.getLastProjectionBranch());
    }

    @Test
    void recordIndexBuild_updatesIndexCommit() {
        WorkspaceProjection existing = new WorkspaceProjection();
        existing.setUsername("alice");
        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.of(existing));
        when(projectionRepo.save(any(WorkspaceProjection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        projectionService.recordIndexBuild("alice", "def5678");

        assertEquals("def5678", existing.getIndexCommitId());
        assertNotNull(existing.getIndexTimestamp());
        verify(projectionRepo).save(existing);

        // In-memory state should also be updated
        UserWorkspaceState ws = workspaceManager.getOrCreateWorkspace("alice");
        assertEquals("def5678", ws.getLastIndexCommit());
    }

    // ── Staleness detection ─────────────────────────────────────────

    @Test
    void isProjectionStale_returnsFalseWhenFresh() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Record projection at the current HEAD
        WorkspaceProjection existing = new WorkspaceProjection();
        existing.setUsername("alice");
        existing.setProjectionCommitId(commitId);
        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.of(existing));

        assertFalse(projectionService.isProjectionStale("alice", "draft"));
    }

    @Test
    void isProjectionStale_returnsTrueWhenStale() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "first");

        // Record projection at the first commit
        WorkspaceProjection existing = new WorkspaceProjection();
        existing.setUsername("alice");
        existing.setProjectionCommitId(first);
        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.of(existing));
        when(projectionRepo.save(any(WorkspaceProjection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Advance HEAD with a new commit
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "second");

        assertTrue(projectionService.isProjectionStale("alice", "draft"));
        // Verify the stale flag was persisted
        assertTrue(existing.isStale());
        verify(projectionRepo).save(existing);
    }

    // ── Projection info ─────────────────────────────────────────────

    @Test
    void getProjectionInfo_returnsExpectedFields() {
        WorkspaceProjection existing = new WorkspaceProjection();
        existing.setUsername("alice");
        existing.setProjectionCommitId("abc1234");
        existing.setProjectionBranch("draft");
        existing.setStale(false);
        when(projectionRepo.findByUsername("alice")).thenReturn(Optional.of(existing));

        Map<String, Object> info = projectionService.getProjectionInfo("alice");

        assertEquals("alice", info.get("username"));
        assertEquals("abc1234", info.get("persistedProjectionCommit"));
        assertEquals("draft", info.get("persistedProjectionBranch"));
        assertEquals(false, info.get("stale"));
        // In-memory fields should be present (null by default)
        assertTrue(info.containsKey("lastProjectionCommit"));
        assertTrue(info.containsKey("lastIndexCommit"));
    }
}
