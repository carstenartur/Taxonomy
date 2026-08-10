package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link WorkspaceContextResolver}. */
@ExtendWith(MockitoExtension.class)
class WorkspaceContextResolverTest {

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    @Mock
    private UserWorkspaceRepository workspaceRepository;

    private WorkspaceContextResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new WorkspaceContextResolver(
                workspaceManager, systemRepositoryService, workspaceRepository);
    }

    @Test
    void nullUsernameReturnsSHARED() {
        assertThat(resolver.resolveForUser(null)).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void blankUsernameReturnsSHARED() {
        assertThat(resolver.resolveForUser("")).isEqualTo(WorkspaceContext.SHARED);
        assertThat(resolver.resolveForUser("  ")).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void anonymousUserReturnsSHARED() {
        assertThat(resolver.resolveForUser("anonymous")).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void userWithoutProvisionedWorkspaceReturnsSHARED() {
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(null);
        assertThat(resolver.resolveForUser("alice")).isEqualTo(WorkspaceContext.SHARED);
    }

    @Test
    void userWithProvisionedWorkspaceReturnsContext() {
        UserWorkspace workspace = workspace("alice", "alice-ws-123", "alice/workspace");
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(workspace);

        WorkspaceContext context = resolver.resolveForUser("alice");

        assertThat(context.username()).isEqualTo("alice");
        assertThat(context.workspaceId()).isEqualTo("alice-ws-123");
        assertThat(context.currentBranch()).isEqualTo("alice/workspace");
    }

    @Test
    void provisionedWorkspaceWithNullBranchFallsBackToSharedBranch() {
        UserWorkspace workspace = workspace("bob", "bob-ws", null);
        when(workspaceManager.findUserWorkspace("bob")).thenReturn(workspace);
        when(systemRepositoryService.getSharedBranch()).thenReturn("draft");

        WorkspaceContext context = resolver.resolveForUser("bob");

        assertThat(context.currentBranch()).isEqualTo("draft");
    }

    @Test
    void sharedContextHasNullWorkspaceId() {
        assertThat(WorkspaceContext.SHARED.username()).isEqualTo("system");
        assertThat(WorkspaceContext.SHARED.workspaceId()).isNull();
        assertThat(WorkspaceContext.SHARED.currentBranch()).isEqualTo("draft");
    }

    @Test
    void differentWorkspacesAreIsolated() {
        UserWorkspace aliceWorkspace = workspace("alice", "alice-ws", "alice/workspace");
        UserWorkspace bobWorkspace = workspace("bob", "bob-ws", "bob/workspace");
        when(workspaceManager.findUserWorkspace("alice")).thenReturn(aliceWorkspace);
        when(workspaceManager.findUserWorkspace("bob")).thenReturn(bobWorkspace);

        WorkspaceContext aliceContext = resolver.resolveForUser("alice");
        WorkspaceContext bobContext = resolver.resolveForUser("bob");

        assertThat(aliceContext.workspaceId()).isNotEqualTo(bobContext.workspaceId());
        assertThat(aliceContext.currentBranch()).isNotEqualTo(bobContext.currentBranch());
    }

    @Test
    void activeWorkspaceProducesExplicitRepositoryContext() {
        UserWorkspace workspace = workspace("alice", "alice-ws", "feature/a");
        workspace.setSourceRepositoryId("repo-a");
        SystemRepository repository = repository("repo-a", "main");
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(workspace);
        when(systemRepositoryService.getRepository("repo-a")).thenReturn(repository);

        RepositoryContext context = resolver.resolveRepositoryContextForUser("alice");

        assertThat(context.repositoryId()).isEqualTo("repo-a");
        assertThat(context.workspaceId()).isEqualTo("alice-ws");
        assertThat(context.branch()).isEqualTo("feature/a");
        assertThat(context.username()).isEqualTo("alice");
        assertThat(context.scope()).isEqualTo(RepositoryScope.WORKSPACE);
    }

    @Test
    void workspaceWithoutCurrentBranchUsesItsSourceRepositoryDefault() {
        UserWorkspace workspace = workspace("alice", "alice-ws", null);
        workspace.setSourceRepositoryId("repo-a");
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(workspace);
        when(systemRepositoryService.getRepository("repo-a"))
                .thenReturn(repository("repo-a", "main"));

        RepositoryContext context = resolver.resolveRepositoryContextForUser("alice");

        assertThat(context.branch()).isEqualTo("main");
        assertThat(context.repositoryId()).isEqualTo("repo-a");
    }

    @Test
    void legacyWorkspaceWithoutSourceRepositoryIsBoundAndPersistedToPrimaryRepository() {
        UserWorkspace workspace = workspace("alice", "legacy-ws", "draft");
        SystemRepository primary = repository("primary-repo", "draft");
        when(workspaceManager.findActiveWorkspace("alice")).thenReturn(workspace);
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(primary);

        RepositoryContext context = resolver.resolveRepositoryContextForUser("alice");

        assertThat(context.repositoryId()).isEqualTo("primary-repo");
        assertThat(context.workspaceId()).isEqualTo("legacy-ws");
        assertThat(context.scope()).isEqualTo(RepositoryScope.WORKSPACE);
        assertThat(workspace.getSourceRepositoryId()).isEqualTo("primary-repo");
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void userWithoutWorkspaceGetsExplicitPrimaryCentralReadContext() {
        when(systemRepositoryService.getPrimaryRepository())
                .thenReturn(repository("primary-repo", "draft"));

        RepositoryContext context = resolver.resolveRepositoryContextForUser("alice");

        assertThat(context.repositoryId()).isEqualTo("primary-repo");
        assertThat(context.workspaceId()).isNull();
        assertThat(context.branch()).isEqualTo("draft");
        assertThat(context.username()).isEqualTo("alice");
        assertThat(context.scope()).isEqualTo(RepositoryScope.CENTRAL_READ);
    }

    @Test
    void repositoryResolutionFailsClosedWhenCatalogIdentityIsIncomplete() {
        SystemRepository primary = repository(null, "draft");
        when(systemRepositoryService.getPrimaryRepository()).thenReturn(primary);

        assertThatThrownBy(() -> resolver.resolveRepositoryContextForUser("alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repositoryId");
    }

    private static UserWorkspace workspace(
            String username, String workspaceId, String branch) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setWorkspaceId(workspaceId);
        workspace.setCurrentBranch(branch);
        return workspace;
    }

    private static SystemRepository repository(String repositoryId, String branch) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        repository.setDefaultBranch(branch);
        return repository;
    }
}
