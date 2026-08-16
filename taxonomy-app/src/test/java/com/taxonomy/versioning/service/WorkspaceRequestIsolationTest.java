package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.versioning.controller.DslWorkspacePreResolutionInterceptor;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkspaceRequestIsolationTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void workspaceAndRepositoryContextAreResolvedOnlyOncePerRequest() {
        WorkspaceContextResolver contextResolver = mock(WorkspaceContextResolver.class);
        when(contextResolver.resolveRepositoryContextForUser("anonymous"))
                .thenReturn(RepositoryContext.workspace(
                        "repository-a", "workspace-1", "draft", "architect"));
        WorkspaceResolver resolver = new WorkspaceResolver(contextResolver);
        bindRequest();

        WorkspaceContext first = resolver.resolveCurrentContext();
        WorkspaceContext second = resolver.resolveCurrentContext();

        assertThat(first).isSameAs(second);
        assertThat(first.repositoryId()).isEqualTo("repository-a");
        assertThat(first.currentBranch()).isEqualTo("draft");
        verify(contextResolver, times(0)).resolveCurrentContext();
        verify(contextResolver, times(1)).resolveRepositoryContextForUser("anonymous");
    }

    @Test
    void successfulProvisioningIsRepeatedAtMostOncePerRequest() {
        DslGitRepositoryFactory repositoryFactory = mock(DslGitRepositoryFactory.class);
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        SystemRepositoryService systemRepositoryService = mock(SystemRepositoryService.class);
        RequestCachingRepositoryStateService service = new RequestCachingRepositoryStateService(
                repositoryFactory, workspaceManager, systemRepositoryService);
        bindRequest();

        service.ensureWorkspaceState("architect");
        service.ensureWorkspaceState("architect");

        verify(workspaceManager, times(1)).getOrCreateWorkspace("architect");
        verifyNoInteractions(repositoryFactory, systemRepositoryService);
    }

    @Test
    void failedPreResolutionStopsDslRequestBeforeControllerExecution() {
        WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        RepositoryStateService repositoryStateService = mock(RepositoryStateService.class);
        DslWorkspacePreResolutionInterceptor interceptor =
                new DslWorkspacePreResolutionInterceptor(resolver, repositoryStateService);
        when(resolver.resolveCurrentUsername()).thenReturn("architect");
        doThrow(new IllegalStateException("workspace database unavailable"))
                .when(repositoryStateService).ensureWorkspaceState("architect");

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace database unavailable");
        verify(resolver, times(0)).resolveCurrentContext();
    }

    @Test
    void sharedFallbackIsRejectedAtDslRequestBoundary() {
        WorkspaceResolver resolver = mock(WorkspaceResolver.class);
        RepositoryStateService repositoryStateService = mock(RepositoryStateService.class);
        DslWorkspacePreResolutionInterceptor interceptor =
                new DslWorkspacePreResolutionInterceptor(resolver, repositoryStateService);
        when(resolver.resolveCurrentUsername()).thenReturn("architect");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(
                RepositoryContext.workspace(
                        "repository-a", "workspace-1", "draft", "architect"));
        when(resolver.resolveCurrentContext()).thenReturn(WorkspaceContext.SHARED);

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not resolve an isolated workspace");
    }

    private static void bindRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
