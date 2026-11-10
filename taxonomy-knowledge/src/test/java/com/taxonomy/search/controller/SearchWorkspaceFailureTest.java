package com.taxonomy.search.controller;

import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchWorkspaceFailureTest {
    private final SearchFacade search = mock(SearchFacade.class);
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final SearchApiController controller = new SearchApiController(
            search, new StaticMessageSource(), workspaces);

    @Test
    void failedWorkspaceResolutionDoesNotSilentlySearchSharedRelations() {
        when(search.isInitialized()).thenReturn(true);
        when(workspaces.resolveCurrentRepositoryContextForRead())
                .thenThrow(new IllegalStateException("private-workspace-details"));
        assertThatThrownBy(() -> controller.graphSearch("query", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search is temporarily unavailable.").hasNoCause();
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void deniedWorkspaceAccessCannotBeReplacedBySharedSearch() {
        when(search.isInitialized()).thenReturn(true);
        var denied = new AccessDeniedException("private-workspace-details");
        when(workspaces.resolveCurrentRepositoryContextForRead()).thenThrow(denied);
        assertThatThrownBy(() -> controller.graphSearch("query", 10)).isSameAs(denied);
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void unreadyWorkspaceRetainsItsConflictStatus() {
        when(search.isInitialized()).thenReturn(true);
        var notReady = new ResponseStatusException(HttpStatus.CONFLICT, "Workspace is not ready");
        when(workspaces.resolveCurrentRepositoryContextForRead()).thenThrow(notReady);
        assertThatThrownBy(() -> controller.graphSearch("query", 10)).isSameAs(notReady);
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void absentWorkspaceContextCannotBeReplacedBySharedSearch() {
        when(search.isInitialized()).thenReturn(true);
        assertThatThrownBy(() -> controller.graphSearch("query", 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search is temporarily unavailable.").hasNoCause();
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void explicitlyResolvedCanonicalCentralContextRemainsSupported() {
        when(search.isInitialized()).thenReturn(true);
        var central = RepositoryContext.centralRead("fixture-repository", "draft", "test-user");
        when(workspaces.resolveCurrentRepositoryContextForRead()).thenReturn(central);
        controller.graphSearch("query", 10);
        verify(search).graphSearch("query", 10, central);
    }
    @Test
    void canonicalForkScopeReachesFacadeWithoutBecomingCentral() {
        when(search.isInitialized()).thenReturn(true);
        var fork = new RepositoryContext("fixture-repository", null, "draft", "test-user",
                com.taxonomy.workspace.service.RepositoryScope.FORK);
        when(workspaces.resolveCurrentRepositoryContextForRead()).thenReturn(fork);
        controller.graphSearch("query", 10);
        verify(search).graphSearch("query", 10, fork);
        verify(workspaces, never()).resolveCurrentContext();
    }

}
