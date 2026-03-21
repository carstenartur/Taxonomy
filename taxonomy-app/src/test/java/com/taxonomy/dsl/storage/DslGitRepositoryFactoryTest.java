package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DslGitRepositoryFactory}.
 *
 * <p>Verifies that the factory creates distinct repository instances
 * per workspace, caches them correctly, and resolves workspace contexts.
 * Uses in-memory repositories (null SessionFactory) for isolation.
 */
class DslGitRepositoryFactoryTest {

    private DslGitRepositoryFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null); // in-memory mode
    }

    @Test
    void getSystemRepository_returnsSameInstance() {
        DslGitRepository first = factory.getSystemRepository();
        DslGitRepository second = factory.getSystemRepository();
        assertSame(first, second, "System repository should be cached");
    }

    @Test
    void getWorkspaceRepository_returnsSameInstanceForSameId() {
        DslGitRepository first = factory.getWorkspaceRepository("ws-123");
        DslGitRepository second = factory.getWorkspaceRepository("ws-123");
        assertSame(first, second, "Same workspace ID should return same instance");
    }

    @Test
    void getWorkspaceRepository_returnsDifferentInstancesForDifferentIds() {
        DslGitRepository repoA = factory.getWorkspaceRepository("alpha");
        DslGitRepository repoB = factory.getWorkspaceRepository("beta");
        assertNotSame(repoA, repoB, "Different workspace IDs should return different instances");
    }

    @Test
    void workspaceRepository_isDifferentFromSystemRepository() {
        DslGitRepository sysRepo = factory.getSystemRepository();
        DslGitRepository wsRepo = factory.getWorkspaceRepository("workspace-1");
        assertNotSame(sysRepo, wsRepo, "Workspace repo should differ from system repo");
    }

    @Test
    void evict_removesFromCache() {
        DslGitRepository before = factory.getWorkspaceRepository("evict-test");
        factory.evict("evict-test");
        DslGitRepository after = factory.getWorkspaceRepository("evict-test");
        assertNotSame(before, after, "After eviction, a new instance should be created");
    }

    @Test
    void resolveRepository_nullContext_returnsSystemRepo() {
        DslGitRepository result = factory.resolveRepository(null);
        assertSame(factory.getSystemRepository(), result);
    }

    @Test
    void resolveRepository_sharedContext_returnsSystemRepo() {
        DslGitRepository result = factory.resolveRepository(WorkspaceContext.SHARED);
        assertSame(factory.getSystemRepository(), result);
    }

    @Test
    void resolveRepository_workspaceContext_returnsWorkspaceRepo() {
        WorkspaceContext ctx = new WorkspaceContext("alice", "ws-abc", "main");
        DslGitRepository result = factory.resolveRepository(ctx);
        assertSame(factory.getWorkspaceRepository("ws-abc"), result);
    }

    @Test
    void repositoryIsolation_commitInOneIsInvisibleInOther() throws IOException {
        DslGitRepository repoA = factory.getWorkspaceRepository("isolated-A");
        DslGitRepository repoB = factory.getWorkspaceRepository("isolated-B");

        String dsl = "meta { language: \"taxdsl\"; }";
        repoA.commitDsl("main", dsl, "alice", "initial");

        // repoB should not see repoA's commit
        assertNull(repoB.getDslAtHead("main"),
                "Commit in workspace A should be invisible in workspace B");

        // repoA should see its own commit
        assertEquals(dsl, repoA.getDslAtHead("main"),
                "Commit in workspace A should be visible in workspace A");
    }

    @Test
    void repositoryIsolation_systemRepoIndependentFromWorkspace() throws IOException {
        DslGitRepository sysRepo = factory.getSystemRepository();
        DslGitRepository wsRepo = factory.getWorkspaceRepository("sys-test");

        String dsl = "meta { language: \"taxdsl\"; }";
        wsRepo.commitDsl("main", dsl, "bob", "workspace commit");

        // System repo should not see workspace commit
        assertNull(sysRepo.getDslAtHead("main"),
                "Workspace commit should be invisible in system repo");
    }
}
