package com.taxonomy.search.service;

import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** High-level search facade; graph relation visibility is explicitly workspace-scoped. */
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

    public boolean isInitialized() {
        return taxonomyService.isInitialized();
    }

    public String getInitStatus() {
        return taxonomyService.getInitStatus();
    }

    public List<TaxonomyNodeDto> fullTextSearch(String query, int maxResults) {
        return searchService.search(query, maxResults);
    }

    public List<TaxonomyNodeDto> semanticSearch(String query, int maxResults) {
        return embeddingService.semanticSearch(query, maxResults);
    }

    public List<TaxonomyNodeDto> hybridSearch(String query, int maxResults) {
        return hybridSearchService.hybridSearch(query, maxResults);
    }

    public List<TaxonomyNodeDto> findSimilarNodes(String code, int topK) {
        return embeddingService.findSimilarNodes(code, topK);
    }

    public GraphSearchResult graphSearch(String query,
                                         int maxResults,
                                         WorkspaceContext workspaceContext) {
        return graphSearchService.graphSearch(query, maxResults, workspaceContext);
    }

    public Map<String, Object> getEmbeddingStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", embeddingService.isEnabled());
        status.put("available", embeddingService.isAvailable());
        status.put("modelUrl", embeddingService.effectiveModelUrl());
        status.put("indexedNodes", embeddingService.indexedNodeCount());
        return status;
    }
}
