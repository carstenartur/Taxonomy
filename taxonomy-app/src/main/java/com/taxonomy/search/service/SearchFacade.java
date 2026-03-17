package com.taxonomy.search.service;

import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * High-level facade that aggregates the search domain services.
 *
 * <p>Provides coarse-grained operations for full-text, semantic,
 * hybrid, and graph search, as well as embedding status, so that
 * the {@code SearchApiController} only needs a single dependency.
 */
@Service
public class SearchFacade {

    private final TaxonomyService taxonomyService;
    private final SearchService searchService;
    private final HybridSearchService hybridSearchService;
    private final LocalEmbeddingService embeddingService;
    private final GraphSearchService graphSearchService;

    public SearchFacade(TaxonomyService taxonomyService,
                        SearchService searchService,
                        HybridSearchService hybridSearchService,
                        LocalEmbeddingService embeddingService,
                        GraphSearchService graphSearchService) {
        this.taxonomyService = taxonomyService;
        this.searchService = searchService;
        this.hybridSearchService = hybridSearchService;
        this.embeddingService = embeddingService;
        this.graphSearchService = graphSearchService;
    }

    /**
     * Check whether the taxonomy has been fully initialized.
     */
    public boolean isInitialized() {
        return taxonomyService.isInitialized();
    }

    /**
     * Returns the current taxonomy initialization status message.
     */
    public String getInitStatus() {
        return taxonomyService.getInitStatus();
    }

    /**
     * Full-text search across taxonomy nodes using Lucene.
     */
    public List<TaxonomyNodeDto> fullTextSearch(String query, int maxResults) {
        return searchService.search(query, maxResults);
    }

    /**
     * Semantic search using embedding similarity (KNN).
     */
    public List<TaxonomyNodeDto> semanticSearch(String query, int maxResults) {
        return embeddingService.semanticSearch(query, maxResults);
    }

    /**
     * Hybrid search combining full-text and semantic results via Reciprocal Rank Fusion.
     */
    public List<TaxonomyNodeDto> hybridSearch(String query, int maxResults) {
        return hybridSearchService.hybridSearch(query, maxResults);
    }

    /**
     * Find taxonomy nodes semantically similar to the given node code.
     */
    public List<TaxonomyNodeDto> findSimilarNodes(String code, int topK) {
        return embeddingService.findSimilarNodes(code, topK);
    }

    /**
     * Graph-semantic search combining node and relation KNN queries.
     */
    public GraphSearchResult graphSearch(String query, int maxResults) {
        return graphSearchService.graphSearch(query, maxResults);
    }

    /**
     * Returns the current status of the local embedding model.
     */
    public Map<String, Object> getEmbeddingStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", embeddingService.isEnabled());
        status.put("available", embeddingService.isAvailable());
        status.put("modelUrl", embeddingService.effectiveModelUrl());
        status.put("indexedNodes", embeddingService.indexedNodeCount());
        return status;
    }
}
