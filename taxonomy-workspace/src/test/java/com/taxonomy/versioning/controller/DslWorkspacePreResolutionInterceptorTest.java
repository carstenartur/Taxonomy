package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DslWorkspacePreResolutionInterceptorTest {

    private static final WorkspaceContext WORKSPACE =
            new WorkspaceContext("architect", "workspace-a", "feature-a");
    private static final RepositoryContext REPOSITORY =
            RepositoryContext.workspace(
                    "repository-a",
                    WORKSPACE.workspaceId(),
                    WORKSPACE.currentBranch(),
                    WORKSPACE.username());

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private RepositoryStateService repositoryStateService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private DslWorkspacePreResolutionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new DslWorkspacePreResolutionInterceptor(
                workspaceResolver, repositoryStateService);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(WORKSPACE.username());
    }

    @Test
    void acceptsMatchingRepositoryAndWorkspaceContexts() {
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(REPOSITORY);
        when(workspaceResolver.resolveCurrentContext()).thenReturn(WORKSPACE);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(repositoryStateService).ensureWorkspaceState(WORKSPACE.username());
    }

    @Test
    void rejectsMissingRepositoryContextWithExplicitInvariantFailure() {
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(null);
        when(workspaceResolver.resolveCurrentContext()).thenReturn(WORKSPACE);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Repository context resolver returned null");
    }

    @Test
    void rejectsRepositoryAndWorkspaceIdentityMismatch() {
        RepositoryContext otherWorkspace = RepositoryContext.workspace(
                REPOSITORY.repositoryId(),
                "workspace-b",
                REPOSITORY.branch(),
                REPOSITORY.username());
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(otherWorkspace);
        when(workspaceResolver.resolveCurrentContext()).thenReturn(WORKSPACE);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different workspace identities");
    }
}
