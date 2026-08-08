package com.taxonomy.search.controller;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchApiControllerSemanticReadinessTest {

    @Mock private SearchFacade searchFacade;
    @Mock private MessageSource messageSource;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private RepositoryStateService repositoryStateService;

    private SearchApiController controller;

    @BeforeEach
    void setUp() {
        controller = new SearchApiController(
                searchFacade,
                messageSource,
                workspaceResolver,
                repositoryStateService);
        when(searchFacade.isInitialized()).thenReturn(true);
    }

    @Test
    void semanticSearchFailsClosedWithIndexDiagnosticsUntilNodesAreReady() {
        when(searchFacade.isSemanticSearchReady()).thenReturn(false);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("available", false);
        status.put("semanticReady", false);
        status.put("indexState", "INDEXING_NODES");
        status.put("indexDetail", "Building taxonomy-node vectors");
        when(searchFacade.getEmbeddingStatus()).thenReturn(status);

        ResponseEntity<?> response = controller.semanticSearch("communications", 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Object rawBody = response.getBody();
        assertThat(rawBody).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) rawBody;
        assertThat(body)
                .containsEntry("indexState", "INDEXING_NODES")
                .containsEntry("semanticReady", false)
                .containsEntry("error", "Semantic search is not ready yet");
        verify(searchFacade, never()).semanticSearch("communications", 20);
    }

    @Test
    void semanticSearchDelegatesOnlyAfterNodeIndexReadiness() {
        when(searchFacade.isSemanticSearchReady()).thenReturn(true);
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode("CO");
        when(searchFacade.semanticSearch("communications", 20))
                .thenReturn(List.of(node));

        var response = controller.semanticSearch("communications", 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(node);
    }
}
