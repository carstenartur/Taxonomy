package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DslReadWorkspaceContextResolverTest {
    private final WorkspaceResolver workspace = mock(WorkspaceResolver.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final DslReadWorkspaceContextResolver resolver = new DslReadWorkspaceContextResolver(workspace,state);

    @Test
    void provisionsBeforeResolvingAndPreservesContext() {
        var context = new WorkspaceContext("alice","workspace","review");
        when(workspace.resolveCurrentContext()).thenReturn(context);
        assertThat(resolver.resolve("alice")).isSameAs(context);
        var order = inOrder(state,workspace);
        order.verify(state).ensureWorkspaceState("alice");
        order.verify(workspace).resolveCurrentContext();
        order.verifyNoMoreInteractions();
    }
    @Test
    void provisioningFailureFallsBackWithoutResolving() {
        doThrow(new IllegalStateException("unavailable")).when(state).ensureWorkspaceState("alice");
        assertThat(resolver.resolve("alice")).isSameAs(WorkspaceContext.SHARED);
        verifyNoInteractions(workspace);
    }
    @Test
    void resolutionFailureFallsBack() {
        when(workspace.resolveCurrentContext()).thenThrow(new IllegalArgumentException("unavailable"));
        assertThat(resolver.resolve("alice")).isSameAs(WorkspaceContext.SHARED);
    }
    @Test
    void nullContextRetainsLegacyResult() {
        assertThat(resolver.resolve("alice")).isNull();
        verify(state).ensureWorkspaceState("alice");
    }
}
