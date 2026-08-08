package com.taxonomy.search.controller;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchApiControllerSemanticReadinessTest {

    private static final String DEFAULT_NOT_READY = "Semantic search is not ready yet";

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

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void semanticSearchFailsClosedWithIndexDiagnosticsUntilNodesAreReady() {
        when(searchFacade.isSemanticSearchReady()).thenReturn(false);
        when(messageSource.getMessage(
                "error.semantic.notReady", null, DEFAULT_NOT_READY,
                LocaleContextHolder.getLocale()))
                .thenReturn(DEFAULT_NOT_READY);
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
                .containsEntry("error", DEFAULT_NOT_READY);
        verify(searchFacade, never()).semanticSearch("communications", 20);
    }

    @Test
    void semanticReadinessFailureUsesTheRequestLocale() {
        LocaleContextHolder.setLocale(Locale.GERMAN);
        when(searchFacade.isSemanticSearchReady()).thenReturn(false);
        when(searchFacade.getEmbeddingStatus()).thenReturn(Map.of("semanticReady", false));
        when(messageSource.getMessage(
                "error.semantic.notReady", null, DEFAULT_NOT_READY, Locale.GERMAN))
                .thenReturn("Semantische Suche ist noch nicht bereit");

        ResponseEntity<?> response = controller.semanticSearch("kommunikation", 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "Semantische Suche ist noch nicht bereit");
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
