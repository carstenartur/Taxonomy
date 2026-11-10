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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Test
    void supportedUnpinnedReaderUsesCentralContextWithoutAllocatingWorkspaceState() {
        interceptor.setWorkspaceProvisionPermission(actor -> false);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/projects/1/architecture-workbench/retained");
        var central = RepositoryContext.centralRead("repository-a", "draft", WORKSPACE.username());
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(central);
        when(workspaceResolver.resolveCurrentContext()).thenReturn(
                new WorkspaceContext(WORKSPACE.username(), null, "draft", "repository-a"));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verifyNoInteractions(repositoryStateService);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/projects/1/copilot-operations/retained-operation",
            "HEAD,/api/projects/1/copilot-operations/retained-operation",
            "GET,/api/projects/1/requirements/2/copilot/latest",
            "HEAD,/api/projects/1/requirements/2/copilot/latest"
    })
    void observationalCopilotReadsUseCentralContextWithoutAllocatingState(String method, String path) {
        interceptor.setWorkspaceProvisionPermission(actor -> false);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.centralRead("repository-a", "draft", WORKSPACE.username()));
        when(workspaceResolver.resolveCurrentContext()).thenReturn(
                new WorkspaceContext(WORKSPACE.username(), null, "draft", "repository-a"));

        assertThatCode(() -> interceptor.preHandle(request, response, new Object()))
                .doesNotThrowAnyException();
        verifyNoInteractions(repositoryStateService);
    }

    @Test
    void readerWithExistingWorkspaceRetainsItsSelectedContext() {
        interceptor.setWorkspaceProvisionPermission(actor -> false);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/projects/1/architecture-workbench/retained");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(REPOSITORY);
        when(workspaceResolver.resolveCurrentContext()).thenReturn(WORKSPACE);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(repositoryStateService).ensureWorkspaceState(WORKSPACE.username());
    }

    @Test
    void implicitCentralReadStillRejectsMismatchedActorBeforeAllocatingState() {
        interceptor.setWorkspaceProvisionPermission(actor -> false);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/projects/1/architecture-workbench/retained");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.centralRead("repository-a", "draft", "someone-else"));
        when(workspaceResolver.resolveCurrentContext()).thenReturn(
                new WorkspaceContext("someone-else", null, "draft", "repository-a"));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("matching repository, branch and actor");
        verifyNoInteractions(repositoryStateService);
    }

    @Test
    void deniedProvisioningRejectsCentralWritesBeforeAllocatingState() {
        interceptor.setWorkspaceProvisionPermission(actor -> false);
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.centralRead("repository-a", "draft", WORKSPACE.username()));
        when(workspaceResolver.resolveCurrentContext()).thenReturn(
                new WorkspaceContext(WORKSPACE.username(), null, "draft", "repository-a"));
        when(request.getMethod()).thenReturn("POST");
        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(repositoryStateService);
    }

    @Test
    void deniedProvisioningRejectsWorkspaceRequiredReadsBeforeAllocatingState() {
        interceptor.setWorkspaceProvisionPermission(actor -> false);
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.centralRead("repository-a", "draft", WORKSPACE.username()));
        when(workspaceResolver.resolveCurrentContext()).thenReturn(
                new WorkspaceContext(WORKSPACE.username(), null, "draft", "repository-a"));
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/dsl/current");
        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(repositoryStateService);
    }

}
