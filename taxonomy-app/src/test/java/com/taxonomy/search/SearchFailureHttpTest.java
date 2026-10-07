package com.taxonomy.search;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.search.controller.SearchApiController;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.shared.config.GlobalExceptionHandler;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.persistence.EntityManager;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real search services, facade, controller and exception translation; no native model or remote provider. */
class SearchFailureHttpTest {
    private final EntityManager manager = mock(EntityManager.class);
    private final LocalEmbeddingService embeddings = spy(new LocalEmbeddingService());
    private final WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
    private final RepositoryStateService repository = mock(RepositoryStateService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var taxonomy = mock(TaxonomyService.class);
        when(taxonomy.isInitialized()).thenReturn(true);
        var initializer = mock(LocalOnnxIndexInitializer.class);
        when(initializer.isNodeSearchReady()).thenReturn(true);
        when(initializer.getState()).thenReturn(LocalOnnxIndexInitializer.State.READY);
        doReturn(true).when(embeddings).isEnabled();
        doReturn(true).when(embeddings).isAvailable();
        var fullText = new SearchService();
        ReflectionTestUtils.setField(fullText, "entityManager", manager);
        ReflectionTestUtils.setField(embeddings, "entityManager", manager);
        var facade = new SearchFacade(taxonomy, fullText,
                new HybridSearchService(fullText, embeddings), embeddings,
                new GraphSearchService(embeddings), initializer);
        when(workspaces.resolveCurrentUsername()).thenReturn("test-user");
        when(workspaces.resolveCurrentContext()).thenReturn(WorkspaceContext.SHARED);
        var messages = new ResourceBundleMessageSource();
        messages.setBasenames("i18n/messages_search", "i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        mvc = standaloneSetup(new SearchApiController(facade, messages, workspaces, repository))
                .setControllerAdvice(new GlobalExceptionHandler(messages)).build();
    }

    @Test
    void failedFullTextAndHybridBackendsReturn503WithoutPrivateDetails() throws Exception {
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager))
                    .thenThrow(new IllegalStateException("private-index-path"));
            for (var path : new String[]{"/api/search", "/api/search/hybrid"}) {
                mvc.perform(get(path).param("q", "private-business-query"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.status").value(503))
                        .andExpect(jsonPath("$.message").value(
                                "Search is temporarily unavailable. Please try again."))
                        .andExpect(content().string(not(containsString("private-"))));
            }
        }
    }

    @Test
    void inferenceFailuresInSemanticAndGraphSearchReturnLocalized503() throws Exception {
        doThrow(new IllegalStateException("private-model-response"))
                .when(embeddings).embedQuery("private-business-query");
        for (var path : new String[]{"/api/search/semantic", "/api/search/graph"}) {
            mvc.perform(get(path).param("q", "private-business-query")
                            .header("Accept-Language", "de"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value(
                            "Die Suche ist vorübergehend nicht verfügbar. Bitte erneut versuchen."))
                    .andExpect(content().string(not(containsString("private-"))));
        }
        verify(embeddings, times(2)).embedQuery("private-business-query");
    }

    @Test
    void similarNodeBackendFailureReturns503() throws Exception {
        when(manager.createQuery(anyString(), eq(com.taxonomy.catalog.model.TaxonomyNode.class)))
                .thenThrow(new IllegalStateException("private-database-response"));
        mvc.perform(get("/api/search/similar/BP"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("private-"))));
    }

    @Test
    void workspaceDenialKeepsIts403Meaning() throws Exception {
        when(workspaces.resolveCurrentContext())
                .thenThrow(new AccessDeniedException("private-workspace"));
        mvc.perform(get("/api/search/graph").param("q", "query"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("private-"))));
        verify(embeddings, never()).embedQuery(anyString());
    }

    @Test
    void workspaceFailureReturns503BeforeRetrieval() throws Exception {
        doThrow(new IllegalStateException("private-workspace"))
                .when(repository).ensureWorkspaceState("test-user");
        mvc.perform(get("/api/search/graph").param("q", "query"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(not(containsString("private-"))));
        verify(embeddings, never()).embedQuery(anyString());
    }
}
