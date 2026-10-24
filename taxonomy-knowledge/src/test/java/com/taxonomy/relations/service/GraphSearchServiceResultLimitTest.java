package com.taxonomy.relations.service;


import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GraphSearchServiceResultLimitTest {

    @Test
    void returnsEmptyWithoutEmbeddingWorkForNonPositiveLimits() {
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        GraphSearchAuthorityService authority = mock(GraphSearchAuthorityService.class);
        GraphSearchService service = new GraphSearchService(embeddingService, authority);

        for (int maxResults : new int[]{0, -1}) {
            GraphSearchResult result = service.graphSearch(
                    "bounded query", maxResults, RepositoryContext.centralRead("fixture-repository", "draft", "system"));

            assertThat(result.getMatchedNodes()).isEmpty();
            assertThat(result.getRelationCountByRoot()).isEmpty();
            assertThat(result.getTopRelationTypes()).isEmpty();
            assertThat(result.getSummary())
                    .isEqualTo("No graph search results were requested.");
        }
        verifyNoInteractions(embeddingService, authority);
    }
}
