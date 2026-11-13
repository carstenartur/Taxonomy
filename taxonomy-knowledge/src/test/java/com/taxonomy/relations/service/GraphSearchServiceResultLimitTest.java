package com.taxonomy.relations.service;


import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GraphSearchServiceResultLimitTest {

    @Test
    void returnsEmptyWithoutEmbeddingWorkForNonPositiveLimits() {
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        GraphSearchAuthorityService authority = mock(GraphSearchAuthorityService.class);
        GraphSearchService service = new GraphSearchService(embeddingService, authority);

        var context = RepositoryContext.centralRead("fixture-repository", "draft", "system");
        for (int maxResults : new int[]{0, -1}) {
            GraphSearchResult result = service.graphSearch(
                    "bounded query", maxResults, context);

            assertThat(result.getMatchedNodes()).isEmpty();
            assertThat(result.getRelationCountByRoot()).isEmpty();
            assertThat(result.getTopRelationTypes()).isEmpty();
            assertThat(result.getSummary())
                    .isEqualTo("No graph search results were requested.");
        }
        verify(authority, times(2)).inspect(context);
        verifyNoInteractions(embeddingService);
    }

    @Test
    void callerLimitCannotSkipSelectedSourceInspectionOrTriggerInferenceWhenDenied() {
        var context = RepositoryContext.workspace("repo-a", "work-a", "draft", "actor");
        var embedding = mock(LocalEmbeddingService.class);
        var authority = mock(GraphSearchAuthorityService.class);
        var vectors = mock(BranchGraphVectors.class);
        var graph = new GraphSearchService(embedding, authority, vectors);
        when(authority.inspectForBranchSelection(context))
                .thenThrow(new com.taxonomy.error.SearchUnavailableException());
        for (int limit : new int[]{-1, 0, 1}) {
            assertThatThrownBy(() -> graph.graphSearch("term", limit, context))
                    .isInstanceOf(com.taxonomy.error.SearchUnavailableException.class);
        }
        verify(authority, times(3)).inspectForBranchSelection(context);
        verifyNoInteractions(embedding, vectors);
    }
}
