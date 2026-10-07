package com.taxonomy.search.controller;

import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchWorkspaceFailureTest {
    private final SearchFacade search = mock(SearchFacade.class);
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final RepositoryStateService repository = mock(RepositoryStateService.class);
    private final SearchApiController controller = new SearchApiController(
            search, new StaticMessageSource(), workspaces, repository);

    @Test
    void failedWorkspaceSetupDoesNotSilentlySearchSharedRelations() {
        when(search.isInitialized()).thenReturn(true);
        when(workspaces.resolveCurrentUsername()).thenReturn("test-user");
        doThrow(new IllegalStateException("private-workspace-details"))
                .when(repository).ensureWorkspaceState("test-user");
        assertThatThrownBy(() -> controller.graphSearch("query", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search is temporarily unavailable.").hasNoCause();
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void deniedWorkspaceAccessCannotBeReplacedBySharedSearch() {
        when(search.isInitialized()).thenReturn(true);
        when(workspaces.resolveCurrentUsername()).thenReturn("test-user");
        var denied = new AccessDeniedException("private-workspace-details");
        when(workspaces.resolveCurrentContext()).thenThrow(denied);
        assertThatThrownBy(() -> controller.graphSearch("query", 10)).isSameAs(denied);
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void absentWorkspaceContextCannotBeReplacedBySharedSearch() {
        when(search.isInitialized()).thenReturn(true);
        when(workspaces.resolveCurrentUsername()).thenReturn("test-user");
        assertThatThrownBy(() -> controller.graphSearch("query", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search is temporarily unavailable.").hasNoCause();
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void explicitlyResolvedSharedContextRemainsSupported() {
        when(search.isInitialized()).thenReturn(true);
        when(workspaces.resolveCurrentUsername()).thenReturn("test-user");
        when(workspaces.resolveCurrentContext()).thenReturn(WorkspaceContext.SHARED);
        controller.graphSearch("query", 10);
        verify(search).graphSearch("query", 10, WorkspaceContext.SHARED);
    }
}
