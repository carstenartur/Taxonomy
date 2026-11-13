package com.taxonomy.search.controller;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.controller.GitHttpPrecondition;
import com.taxonomy.search.service.SearchFacade;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    private final ObjectMapper json;

    public SearchApiController(SearchFacade searchFacade,
                               MessageSource messageSource,
                               WorkspaceResolver workspaceResolver, ObjectMapper json) {
        this.searchFacade = searchFacade;
        this.messageSource = messageSource;
        this.workspaceResolver = workspaceResolver;
        this.json = json;
    }

    @Operation(summary = "Full-text search", description = "Search taxonomy nodes using full-text Lucene search", tags = {"Search"})
    @GetMapping("/search")
    public ResponseEntity<List<TaxonomyNodeDto>> search(
            @Parameter(description = "Search query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "50") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return referenceResults(searchFacade.fullTextSearch(q, maxResults));
    }

    @Operation(summary = "Semantic search", description = "Search taxonomy nodes using embedding similarity (KNN). Requires a ready local embedding index.", tags = {"Search"})
    @GetMapping("/search/semantic")
    public ResponseEntity<List<TaxonomyNodeDto>> semanticSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        guard = checkSemanticReady();
        if (guard != null) return guard;
        return referenceResults(searchFacade.semanticSearch(q, maxResults));
    }

    @Operation(summary = "Hybrid search", description = "Combines full-text Lucene and semantic KNN results via Reciprocal Rank Fusion. Falls back to full-text only when embedding is unavailable.", tags = {"Search"})
    @GetMapping("/search/hybrid")
    public ResponseEntity<List<TaxonomyNodeDto>> hybridSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "20") int maxResults) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        return referenceResults(searchFacade.hybridSearch(q, maxResults));
    }

    @Operation(summary = "Find similar nodes", description = "Find taxonomy nodes semantically similar to a given node", tags = {"Search"})
    @GetMapping("/search/similar/{code}")
    public ResponseEntity<List<TaxonomyNodeDto>> findSimilar(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum number of similar nodes") @RequestParam(defaultValue = "10") int topK) {
        ResponseEntity<List<TaxonomyNodeDto>> guard = checkInitialized();
        if (guard != null) return guard;
        guard = checkSemanticReady();
        if (guard != null) return guard;
        return referenceResults(searchFacade.findSimilarNodes(code, topK));
    }

    private ResponseEntity<List<TaxonomyNodeDto>> referenceResults(List<TaxonomyNodeDto> rows) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Taxonomy-Source-Scope", "GLOBAL_REFERENCE")
                .header("X-Taxonomy-Source-State", "READY")
                .eTag("W/\"" + StableIdentityHash.sha256(json.writeValueAsString(rows)) + "\"").body(rows);
    }

    @Operation(summary = "Embedding model status", description = "Returns model and semantic-index readiness independently", tags = {"Embedding"})
    @GetMapping("/embedding/status")
    public ResponseEntity<Map<String, Object>> embeddingStatus() {
        return ResponseEntity.ok(searchFacade.getEmbeddingStatus());
    }

    @Operation(summary = "Selected graph search readiness", description = "Checks the index used by the selected repository and branch.")
    @GetMapping("/search/graph/readiness")
    public ResponseEntity<GraphSearchService.Readiness> graphReadiness() {
        var status = searchFacade.graphReadiness(currentRepositoryContext());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status);
    }

    @Operation(summary = "Graph-semantic search", description = "Returns the selected repository, branch, commit, and vector provenance. An ambiguous selection or stale commit pin returns 409. Unavailable search authority returns 503. graphReady reports global prerequisites only.", tags = {"Search"})
    @GetMapping("/search/graph")
    public ResponseEntity<GraphSearchResult> graphSearch(
            @Parameter(description = "Natural-language query") @RequestParam String q,
            @Parameter(description = "Maximum number of node results") @RequestParam(defaultValue = "20") int maxResults,
            @Parameter(description = "Expected current branch commit") @RequestParam(required = false) String commitId) {
        ResponseEntity<GraphSearchResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (q == null || q.isBlank()) return ResponseEntity.badRequest().build();
        String expected;
        try {
            expected = commitId == null ? null : GitHttpPrecondition.commitId(commitId);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        GraphSearchResult result = searchFacade.graphSearch(q, maxResults, currentRepositoryContext());
        String actual = result.getSource().sourceCommit();
        if (expected != null && !expected.equals(actual)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                    .header("X-Taxonomy-Source-State", "STALE").header("X-Taxonomy-Commit", actual).build();
        }
        String tag = StableIdentityHash.sha256(json.writeValueAsString(result));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("W/\"" + tag + "\"")
                .header("X-Taxonomy-Source-State", "READY").header("X-Taxonomy-Commit", actual).body(result);
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

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkSemanticReady() {
        if (!searchFacade.isSemanticSearchReady()) {
            Map<String, Object> body = new LinkedHashMap<>(searchFacade.getEmbeddingStatus());
            body.put("error", messageSource.getMessage("error.semantic.notReady", null,
                    "Semantic search is not ready yet", LocaleContextHolder.getLocale()));
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }

    private RepositoryContext currentRepositoryContext() {
        try {
            RepositoryContext context = workspaceResolver.resolveUnambiguousRepositoryForRead();
            if (context == null) throw new SearchUnavailableException();
            return context;
        } catch (AccessDeniedException | ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            log.warn("Search workspace resolution failed (code=SEARCH_CONTEXT_UNAVAILABLE)");
            throw new SearchUnavailableException();
        }
    }
}
