package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyNodeDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Hybrid search service that combines full-text Lucene search with semantic KNN
 * embedding search using Reciprocal Rank Fusion (RRF).
 *
 * <h2>Architecture</h2>
 * <p>Mirrors the {@code hybridSearch()} approach described in the sandbox project's
 * {@code GitDatabaseQueryService} plan:
 * <ol>
 *   <li>Run semantic KNN search ({@link LocalEmbeddingService#semanticSearch}) — finds
 *       conceptually similar nodes even with no lexical overlap.</li>
 *   <li>Run full-text Lucene search ({@link SearchService#search}) — finds exact keyword
 *       matches in node names and descriptions.</li>
 *   <li>Merge both lists via {@link RankFusionUtil#fuse} (RRF) — produces a single
 *       re-ranked list without requiring score calibration.</li>
 * </ol>
 *
 * <h2>Graceful degradation</h2>
 * <p>When the embedding model is not available ({@link LocalEmbeddingService#isAvailable()}
 * returns {@code false}), hybrid search transparently falls back to full-text only.
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final SearchService searchService;
    private final LocalEmbeddingService embeddingService;

    public HybridSearchService(SearchService searchService,
                                LocalEmbeddingService embeddingService) {
        this.searchService = searchService;
        this.embeddingService = embeddingService;
    }

    /**
     * Performs hybrid search: combines full-text Lucene results with semantic KNN results
     * using Reciprocal Rank Fusion.
     *
     * <p>When embedding is unavailable, transparently falls back to full-text search only.
     *
     * @param queryText  natural-language query or keyword (e.g. "satellite communications")
     * @param maxResults maximum number of results to return
     * @return merged and re-ranked list of taxonomy node DTOs
     */
    public List<TaxonomyNodeDto> hybridSearch(String queryText, int maxResults) {
        List<TaxonomyNodeDto> fullText = searchService.search(queryText, maxResults);

        if (!embeddingService.isAvailable()) {
            log.debug("Embedding unavailable; hybrid search returning full-text only results");
            return fullText;
        }

        List<TaxonomyNodeDto> semantic = embeddingService.semanticSearch(queryText, maxResults);

        if (semantic.isEmpty()) {
            return fullText;
        }

        List<TaxonomyNodeDto> fused = RankFusionUtil.fuse(semantic, fullText, maxResults);
        log.debug("Hybrid search for '{}': semantic={}, fullText={}, fused={}",
                queryText, semantic.size(), fullText.size(), fused.size());
        return fused;
    }
}
