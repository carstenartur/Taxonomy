package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceContextResolverRequestPinningTest {

    @Mock
    private WorkspaceManager workspaceManager;

    @Mock
    private SystemRepositoryService systemRepositoryService;

    @Mock
    private UserWorkspaceRepository workspaceRepository;

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void explicitWorkspaceHeaderWinsOverAnotherSessionsActiveWorkspace() {
        UserWorkspace requested = workspace("alice", "workspace-a", "feature/a", "repo-a");
        when(workspaceManager.getWorkspaceById("workspace-a")).thenReturn(requested);
        when(systemRepositoryService.getRepository("repo-a"))
                .thenReturn(repository("repo-a", "main"));
        requestWithWorkspaceHeader("workspace-a");

        WorkspaceContextResolver resolver = resolver();
        RepositoryContext context = resolver.resolveRepositoryContextForUser("alice");

        assertThat(context.workspaceId()).isEqualTo("workspace-a");
        assertThat(context.repositoryId()).isEqualTo("repo-a");
        assertThat(context.branch()).isEqualTo("feature/a");
        verify(workspaceManager, never()).findActiveWorkspace("alice");
    }

    @Test
    void eventSourceWorkspaceQueryPinsTheSameExactContext() {
        UserWorkspace requested = workspace("alice", "workspace-sse", "feature/sse", "repo-sse");
        when(workspaceManager.getWorkspaceById("workspace-sse")).thenReturn(requested);
        when(systemRepositoryService.getRepository("repo-sse"))
                .thenReturn(repository("repo-sse", "main"));
        requestWithWorkspaceQuery("workspace-sse");

        RepositoryContext context = resolver().resolveRepositoryContextForUser("alice");

        assertThat(context.workspaceId()).isEqualTo("workspace-sse");
        assertThat(context.repositoryId()).isEqualTo("repo-sse");
        assertThat(context.branch()).isEqualTo("feature/sse");
        verify(workspaceManager, never()).findActiveWorkspace("alice");
    }

    @Test
    void explicitHeaderTakesPrecedenceOverEventSourceQuery() {
        UserWorkspace requested = workspace("alice", "workspace-header", "feature/header", "repo-header");
        when(workspaceManager.getWorkspaceById("workspace-header")).thenReturn(requested);
        when(systemRepositoryService.getRepository("repo-header"))
                .thenReturn(repository("repo-header", "main"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, "workspace-header");
        request.addParameter(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, "workspace-query");
        bind(request);

        RepositoryContext context = resolver().resolveRepositoryContextForUser("alice");

        assertThat(context.workspaceId()).isEqualTo("workspace-header");
        verify(workspaceManager, never()).getWorkspaceById("workspace-query");
    }

    @Test
    void explicitWorkspaceHeaderCannotSelectAnotherUsersWorkspace() {
        UserWorkspace foreign = workspace("bob", "workspace-b", "feature/b", "repo-b");
        when(workspaceManager.getWorkspaceById("workspace-b")).thenReturn(foreign);
        requestWithWorkspaceHeader("workspace-b");

        WorkspaceContextResolver resolver = resolver();

        assertThatThrownBy(() -> resolver.resolveRepositoryContextForUser("alice"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not available");
        verify(systemRepositoryService, never()).getRepository("repo-b");
    }

    private WorkspaceContextResolver resolver() {
        return new WorkspaceContextResolver(
                workspaceManager, systemRepositoryService, workspaceRepository);
    }

    private static void requestWithWorkspaceHeader(String workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, workspaceId);
        bind(request);
    }

    private static void requestWithWorkspaceQuery(String workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, workspaceId);
        bind(request);
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static UserWorkspace workspace(
            String username, String workspaceId, String branch, String repositoryId) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setWorkspaceId(workspaceId);
        workspace.setCurrentBranch(branch);
        workspace.setSourceRepositoryId(repositoryId);
        return workspace;
    }

    private static SystemRepository repository(String repositoryId, String branch) {
        SystemRepository repository = new SystemRepository();
        repository.setRepositoryId(repositoryId);
        repository.setDefaultBranch(branch);
        return repository;
    }
}
