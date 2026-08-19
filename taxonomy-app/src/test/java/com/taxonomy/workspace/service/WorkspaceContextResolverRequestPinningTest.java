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
        requestWithWorkspace("workspace-a");

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(
                workspaceManager, systemRepositoryService, workspaceRepository);
        RepositoryContext context = resolver.resolveRepositoryContextForUser("alice");

        assertThat(context.workspaceId()).isEqualTo("workspace-a");
        assertThat(context.repositoryId()).isEqualTo("repo-a");
        assertThat(context.branch()).isEqualTo("feature/a");
        verify(workspaceManager, never()).findActiveWorkspace("alice");
    }

    @Test
    void explicitWorkspaceHeaderCannotSelectAnotherUsersWorkspace() {
        UserWorkspace foreign = workspace("bob", "workspace-b", "feature/b", "repo-b");
        when(workspaceManager.getWorkspaceById("workspace-b")).thenReturn(foreign);
        requestWithWorkspace("workspace-b");

        WorkspaceContextResolver resolver = new WorkspaceContextResolver(
                workspaceManager, systemRepositoryService, workspaceRepository);

        assertThatThrownBy(() -> resolver.resolveRepositoryContextForUser("alice"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not available");
        verify(systemRepositoryService, never()).getRepository("repo-b");
    }

    private static void requestWithWorkspace(String workspaceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, workspaceId);
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
