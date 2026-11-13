package com.taxonomy.search.controller;

import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
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
            search, new StaticMessageSource(), workspaces, JsonMapper.builder().build());

    @Test
    void failedWorkspaceResolutionDoesNotSilentlySearchSharedRelations() {
        when(search.isInitialized()).thenReturn(true);
        when(workspaces.resolveUnambiguousRepositoryForRead())
                .thenThrow(new IllegalStateException("private-workspace-details"));
        assertThatThrownBy(() -> controller.graphSearch("query", 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search is temporarily unavailable.").hasNoCause();
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void deniedWorkspaceAccessCannotBeReplacedBySharedSearch() {
        when(search.isInitialized()).thenReturn(true);
        var denied = new AccessDeniedException("private-workspace-details");
        when(workspaces.resolveUnambiguousRepositoryForRead()).thenThrow(denied);
        assertThatThrownBy(() -> controller.graphSearch("query", 10, null)).isSameAs(denied);
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void unreadyWorkspaceRetainsItsConflictStatus() {
        when(search.isInitialized()).thenReturn(true);
        var notReady = new ResponseStatusException(HttpStatus.CONFLICT, "Workspace is not ready");
        when(workspaces.resolveUnambiguousRepositoryForRead()).thenThrow(notReady);
        assertThatThrownBy(() -> controller.graphSearch("query", 10, null)).isSameAs(notReady);
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void absentWorkspaceContextCannotBeReplacedBySharedSearch() {
        when(search.isInitialized()).thenReturn(true);
        assertThatThrownBy(() -> controller.graphSearch("query", 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Search is temporarily unavailable.").hasNoCause();
        verify(search, never()).graphSearch(anyString(), anyInt(), any());
    }

    @Test
    void explicitlyResolvedCanonicalCentralContextRemainsSupported() {
        when(search.isInitialized()).thenReturn(true);
        var central = RepositoryContext.centralRead("fixture-repository", "draft", "test-user");
        when(workspaces.resolveUnambiguousRepositoryForRead()).thenReturn(central);
        when(search.graphSearch("query", 10, central)).thenReturn(selectedResult(central));
        controller.graphSearch("query", 10, null);
        verify(search).graphSearch("query", 10, central);
    }
    @Test
    void canonicalForkScopeReachesFacadeWithoutBecomingCentral() {
        when(search.isInitialized()).thenReturn(true);
        var fork = new RepositoryContext("fixture-repository", null, "draft", "test-user",
                com.taxonomy.workspace.service.RepositoryScope.FORK);
        when(workspaces.resolveUnambiguousRepositoryForRead()).thenReturn(fork);
        when(search.graphSearch("query", 10, fork)).thenReturn(selectedResult(fork));
        controller.graphSearch("query", 10, null);
        verify(search).graphSearch("query", 10, fork);
        verify(workspaces, never()).resolveCurrentContext();
    }

    private GraphSearchResult selectedResult(RepositoryContext selected) {
        var result = new GraphSearchResult();
        result.setSource(new GraphSearchResult.Source(selected.repositoryId(), selected.workspaceId(), selected.branch(),
                "a".repeat(40), "PROJECTION", GraphSearchResult.VectorSource.NO_INFERENCE, null, null, null));
        return result;
    }
}
