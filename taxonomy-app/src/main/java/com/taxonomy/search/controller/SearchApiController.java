package com.taxonomy.search.controller;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    private final SearchFacade searchFacade;
    private final MessageSource messageSource;
    private final WorkspaceResolver workspaceResolver;

    public SearchApiController(SearchFacade searchFacade,
                               MessageSource messageSource,
                               WorkspaceResolver workspaceResolver) {
        this.searchFacade = searchFacade;
        this.messageSource = messageSource;
        this.workspaceResolver = workspaceResolver;
    }

    @Operation(summary = "Full-text search", tags = {"Search"})
    @GetMapping("/search")
    public ResponseEntity<List<TaxonomyNodeDto>> search(
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Maximum number of results")
            @RequestParam(defaultValue = "50") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.fullTextSearch(q, maxResults));
    }

    @Operation(summary = "Semantic search", tags = {"Search"})
    @GetMapping("/search/semantic")
    public ResponseEntity<List<TaxonomyNodeDto>> semanticSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.semanticSearch(q, maxResults));
    }

    @Operation(summary = "Hybrid search", tags = {"Search"})
    @GetMapping("/search/hybrid")
    public ResponseEntity<List<TaxonomyNodeDto>> hybridSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.hybridSearch(q, maxResults));
    }

    @Operation(summary = "Find similar nodes", tags = {"Search"})
    @GetMapping("/search/similar/{code}")
    public ResponseEntity<List<TaxonomyNodeDto>> findSimilar(
            @PathVariable String code,
            @RequestParam(defaultValue = "10") int topK) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(searchFacade.findSimilarNodes(code, topK));
    }

    @Operation(summary = "Embedding model status", tags = {"Embedding"})
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        return ResponseEntity.ok(searchFacade.getEmbeddingStatus());
    }

    @Operation(summary = "Graph-semantic search", tags = {"Search"})
    @GetMapping("/search/graph")
    public ResponseEntity<GraphSearchResult> graphSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<GraphSearchResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        return ResponseEntity.ok(searchFacade.graphSearch(q, maxResults, context));
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!searchFacade.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", messageSource.getMessage("error.loading", null,
                    "Taxonomy data is still loading. Please wait.",
                    LocaleContextHolder.getLocale()));
            body.put("status", searchFacade.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }
}
