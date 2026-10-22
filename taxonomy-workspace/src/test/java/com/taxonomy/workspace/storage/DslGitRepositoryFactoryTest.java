package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link DslGitRepositoryFactory}. */
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
    void getCentralRepository_isStableAndIsolatedByRepositoryId() {
        DslGitRepository first = factory.getCentralRepository("repo-a");
        DslGitRepository again = factory.getCentralRepository("repo-a");
        DslGitRepository other = factory.getCentralRepository("repo-b");

        assertSame(first, again);
        assertNotSame(first, other);
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
    void resolveRepository_nullLegacyContext_returnsSystemRepo() {
        DslGitRepository result = factory.resolveRepository((WorkspaceContext) null);
        assertSame(factory.getSystemRepository(), result);
    }

    @Test
    void resolveRepository_sharedLegacyContext_returnsSystemRepo() {
        DslGitRepository result = factory.resolveRepository(WorkspaceContext.SHARED);
        assertSame(factory.getSystemRepository(), result);
    }

    @Test
    void resolveRepository_legacyWorkspaceContext_returnsWorkspaceRepo() {
        WorkspaceContext ctx = new WorkspaceContext("alice", "ws-abc", "main");
        DslGitRepository result = factory.resolveRepository(ctx);
        assertSame(factory.getWorkspaceRepository("ws-abc"), result);
    }

    @Test
    void explicitWorkspaceContextNeverInfersPrimarySeed() throws IOException {
        factory.getSystemRepository().commitDsl(
                "draft", "meta { language: \"primary\"; }", "system", "primary");

        RepositoryContext context = new RepositoryContext(
                "repo-a", "explicit-workspace", "main", "alice", RepositoryScope.WORKSPACE);
        DslGitRepository workspace = factory.resolveRepository(context);

        assertNull(workspace.getDslAtHead("draft"),
                "Explicit context must not seed an unknown workspace from the primary repository");
    }

    @ParameterizedTest
    @ValueSource(strings = {"repo-a", " repo-a "})
    void createWorkspaceRepositorySeedsFromSelectedCentralRepository(String sourceRepositoryId) throws IOException {
        String dsl = "meta { language: \"selected\"; }";
        String sourceCommit = factory.getCentralRepository("repo-a")
                .commitDsl("main", dsl, "alice", "source");
        factory.getCentralRepository("repo-b")
                .commitDsl("main", "meta { language: \"other\"; }", "bob", "other");

        DslGitRepository workspace =
                factory.createWorkspaceRepository("working-copy", sourceRepositoryId, "main");

        assertEquals(dsl, workspace.getDslAtHead("draft"));
        CommitProvenance.Source source = workspace.getProvenance(
                workspace.getHeadCommit("draft")).sources().getFirst();
        assertEquals("repo-a", source.repositoryId());
        assertEquals("main", source.branch());
        assertEquals(sourceCommit, source.commitId());
    }

    @Test
    void legacyWorkspaceSeedRecordsPrimaryCatalogIdRatherThanDisplayDescription() throws IOException {
        SystemRepository primary = new SystemRepository();
        primary.setRepositoryId("primary-catalog-id");
        SystemRepositoryService catalog = mock(SystemRepositoryService.class);
        when(catalog.getPrimaryRepository()).thenReturn(primary);

        try (DslGitRepositoryFactory catalogFactory = new DslGitRepositoryFactory(null, catalog)) {
            String sourceCommit = catalogFactory.getSystemRepository().commitDsl(
                    "draft", "meta { language: \"primary\"; }", "system", "source");

            DslGitRepository workspace = catalogFactory.getWorkspaceRepository("personal");

            CommitProvenance.Source source = workspace.getProvenance(
                    workspace.getHeadCommit("draft")).sources().getFirst();
            assertEquals("primary-catalog-id", source.repositoryId());
            assertEquals("draft", source.branch());
            assertEquals(sourceCommit, source.commitId());
        }
    }

    @Test
    void inMemoryLegacySeedUsesExplicitCompatibilityIdentity() throws IOException {
        String sourceCommit = factory.getSystemRepository().commitDsl(
                "draft", "meta { language: \"primary\"; }", "system", "source");

        DslGitRepository workspace = factory.getWorkspaceRepository("legacy-personal");

        CommitProvenance.Source source = workspace.getProvenance(
                workspace.getHeadCommit("draft")).sources().getFirst();
        assertEquals(WorkspaceContext.LEGACY_REPOSITORY_ID, source.repositoryId());
        assertEquals(sourceCommit, source.commitId());
    }

    @Test
    void repositoryIsolation_commitInOneIsInvisibleInOther() throws IOException {
        DslGitRepository repoA = factory.getWorkspaceRepository("isolated-A");
        DslGitRepository repoB = factory.getWorkspaceRepository("isolated-B");

        String dsl = "meta { language: \"taxdsl\"; }";
        repoA.commitDsl("main", dsl, "alice", "initial");

        assertNull(repoB.getDslAtHead("main"),
                "Commit in workspace A should be invisible in workspace B");
        assertEquals(dsl, repoA.getDslAtHead("main"),
                "Commit in workspace A should be visible in workspace A");
    }

    @Test
    void repositoryIsolation_systemRepoIndependentFromWorkspace() throws IOException {
        DslGitRepository sysRepo = factory.getSystemRepository();
        DslGitRepository wsRepo = factory.getWorkspaceRepository("sys-test");

        String dsl = "meta { language: \"taxdsl\"; }";
        wsRepo.commitDsl("main", dsl, "bob", "workspace commit");

        assertNull(sysRepo.getDslAtHead("main"),
                "Workspace commit should be invisible in system repo");
    }
}
