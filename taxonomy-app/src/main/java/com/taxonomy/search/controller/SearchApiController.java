package com.taxonomy.search.controller;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Search")
public class SearchApiController {

    private static final Logger log = LoggerFactory.getLogger(SearchApiController.class);

    private final SearchFacade searchFacade;
    private final MessageSource messageSource;
    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;

    public SearchApiController(SearchFacade searchFacade,
                               MessageSource messageSource,
                               WorkspaceResolver workspaceResolver,
                               RepositoryStateService repositoryStateService) {
        this.searchFacade = searchFacade;
        this.messageSource = messageSource;
        this.workspaceResolver = workspaceResolver;
        this.repositoryStateService = repositoryStateService;
    }

    @Operation(summary = "Full-text search", description = "Search taxonomy nodes using full-text Lucene search", tags = {"Search"})
    @GetMapping("/search")
    public ResponseEntity<List<TaxonomyNodeDto>> search(
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "50") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.fullTextSearch(q, maxResults));
    }

    @Operation(summary = "Semantic search", description = "Search taxonomy nodes using embedding similarity (KNN). Requires LOCAL_ONNX or embedding enabled.", tags = {"Search"})
    @GetMapping("/search/semantic")
    public ResponseEntity<List<TaxonomyNodeDto>> semanticSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.semanticSearch(q, maxResults));
    }

    @Operation(summary = "Hybrid search", description = "Combines full-text Lucene and semantic KNN results via Reciprocal Rank Fusion. Falls back to full-text only when embedding is unavailable.", tags = {"Search"})
    @GetMapping("/search/hybrid")
    public ResponseEntity<List<TaxonomyNodeDto>> hybridSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.hybridSearch(q, maxResults));
    }

    @Operation(summary = "Find similar nodes", description = "Find taxonomy nodes semantically similar to a given node", tags = {"Search"})
    @GetMapping("/search/similar/{code}")
    public ResponseEntity<List<TaxonomyNodeDto>> findSimilar(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum number of similar nodes") @RequestParam(defaultValue = "10") int topK) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(searchFacade.findSimilarNodes(code, topK));
    }

    @Operation(summary = "Embedding model status", description = "Returns the current status of the local embedding model", tags = {"Embedding"})
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        return ResponseEntity.ok(searchFacade.getEmbeddingStatus());
    }

    @Operation(summary = "Graph-semantic search", description = "Combines node and relation KNN queries with explicit workspace relation visibility.", tags = {"Search"})
    @GetMapping("/search/graph")
    public ResponseEntity<GraphSearchResult> graphSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of node results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<GraphSearchResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.graphSearch(q, maxResults, currentWorkspaceContext()));
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!searchFacade.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", messageSource.getMessage("error.loading", null,
                    "Taxonomy data is still loading. Please wait.", LocaleContextHolder.getLocale()));
            body.put("status", searchFacade.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }

    private WorkspaceContext currentWorkspaceContext() {
        String username = workspaceResolver.resolveCurrentUsername();
        try {
            repositoryStateService.ensureWorkspaceState(username);
            WorkspaceContext context = workspaceResolver.resolveCurrentContext();
            return context != null ? context : WorkspaceContext.SHARED;
        } catch (Exception error) {
            log.warn("Falling back to shared search context for user '{}': {}",
                    username, error.toString());
            return WorkspaceContext.SHARED;
        }
    }
}
