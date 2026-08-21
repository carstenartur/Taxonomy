package com.taxonomy.workspace.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExplicitWorkspacePinValidationInterceptorTest {

    @Mock
    private WorkspaceResolver workspaceResolver;

    private ExplicitWorkspacePinValidationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new ExplicitWorkspacePinValidationInterceptor(workspaceResolver);
    }

    @Test
    void requestWithoutExplicitPinKeepsEndpointSpecificResolution() {
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object())).isTrue();

        verify(workspaceResolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void headerPinMustResolveToTheExactOwnedWorkspace() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(WorkspaceContextResolver.WORKSPACE_HEADER, " workspace-a ");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.workspace(
                        "repo-a", "workspace-a", "feature/a", "alice"));

        assertThat(interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void queryPinUsesTheSameAuthorizationBoundary() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter(
                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER,
                "workspace-sse");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.workspace(
                        "repo-a", "workspace-sse", "feature/sse", "alice"));

        assertThat(interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void deniedPinPropagatesBeforeControllerFallbacksCanRun() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                WorkspaceContextResolver.WORKSPACE_HEADER,
                "foreign-workspace");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenThrow(new AccessDeniedException("foreign workspace"));

        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("foreign workspace");
    }

    @Test
    void mismatchedOrCentralResolutionFailsClosed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter(
                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER,
                "workspace-a");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.centralRead("repo-a", "main", "alice"));

        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void headerKeepsPrecedenceOverAConflictingQueryPin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                WorkspaceContextResolver.WORKSPACE_HEADER,
                "workspace-header");
        request.addParameter(
                WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER,
                "workspace-query");
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.workspace(
                        "repo-a", "workspace-header", "feature/header", "alice"));

        assertThat(interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
