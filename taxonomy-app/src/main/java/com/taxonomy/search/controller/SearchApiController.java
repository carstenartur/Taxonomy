package com.taxonomy.search.controller;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.search.service.SearchFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Search")
public class SearchApiController {

    private final SearchFacade searchFacade;

    public SearchApiController(SearchFacade searchFacade) {
        this.searchFacade = searchFacade;
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

    /**
     * Semantic search across the full taxonomy using embedding similarity.
     * Returns nodes ranked by cosine similarity to {@code q}.
     * Requires {@code LLM_PROVIDER=LOCAL_ONNX} or {@code TAXONOMY_EMBEDDING_ENABLED=true}.
     */
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

    /**
     * Hybrid search: combines full-text Lucene and semantic KNN results via
     * Reciprocal Rank Fusion.  Falls back to full-text only when embedding is unavailable.
     */
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

    /**
     * Find taxonomy nodes semantically similar to the node identified by {@code code}.
     */
    @Operation(summary = "Find similar nodes", description = "Find taxonomy nodes semantically similar to a given node", tags = {"Search"})
    @GetMapping("/search/similar/{code}")
    public ResponseEntity<List<TaxonomyNodeDto>> findSimilar(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum number of similar nodes") @RequestParam(defaultValue = "10") int topK) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        return ResponseEntity.ok(searchFacade.findSimilarNodes(code, topK));
    }

    /**
     * Returns the current status of the local embedding model.
     */
    @Operation(summary = "Embedding model status", description = "Returns the current status of the local embedding model", tags = {"Embedding"})
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        return ResponseEntity.ok(searchFacade.getEmbeddingStatus());
    }

    /**
     * Graph-semantic search: combines node and relation KNN queries to answer
     * graph-structural questions.
     */
    @Operation(summary = "Graph-semantic search", description = "Combines node and relation KNN queries to answer graph-structural questions. Returns matched nodes, per-root relation counts, top relation types, and a summary.", tags = {"Search"})
    @GetMapping("/search/graph")
    public ResponseEntity<GraphSearchResult> graphSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of node results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<GraphSearchResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(searchFacade.graphSearch(q, maxResults));
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!searchFacade.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Taxonomy data is still loading. Please wait.");
            body.put("status", searchFacade.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }
}
