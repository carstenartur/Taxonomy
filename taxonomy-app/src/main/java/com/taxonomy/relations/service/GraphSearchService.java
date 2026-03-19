package com.taxonomy.relations.service;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import com.taxonomy.shared.service.LocalEmbeddingService;

/**
 * Graph-semantic search service that combines node and relation KNN queries to answer
 * graph-structural questions (e.g. "which Business Processes are supported the most?").
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Embed the query text with DJL / bge-small-en-v1.5.</li>
 *   <li>Search {@link TaxonomyNode} index with {@code f.knn()} — finds conceptually
 *       similar nodes.</li>
 *   <li>Search {@link TaxonomyRelation} index with {@code f.knn()} — finds relations
 *       whose enriched text matches the query.</li>
 *   <li>Aggregate relation hits by taxonomy root and relation type.</li>
 *   <li>Build a summary describing which taxonomy root has the most matching relations.</li>
 * </ol>
 *
 * <p>Graceful degradation: when the DJL model is unavailable, falls back to an empty result.
 */
@Service
public class GraphSearchService {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchService.class);

    private final LocalEmbeddingService embeddingService;
    private final WorkspaceContextResolver contextResolver;

    @PersistenceContext
    private EntityManager entityManager;

    public GraphSearchService(LocalEmbeddingService embeddingService,
                               WorkspaceContextResolver contextResolver) {
        this.embeddingService = embeddingService;
        this.contextResolver = contextResolver;
    }

    /**
     * Perform a graph-semantic search combining node and relation KNN queries.
     *
     * @param queryText  natural-language query (e.g. "which Business Processes are most supported?")
     * @param maxResults maximum number of node results to return (default 20)
     * @return {@link GraphSearchResult} with matched nodes, graph statistics, and summary
     */
    @Transactional(readOnly = true)
    public GraphSearchResult graphSearch(String queryText, int maxResults) {
        if (!embeddingService.isAvailable()) {
            return new GraphSearchResult(Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyMap(), "Semantic search is not available (embedding model not loaded).");
        }

        try {
            float[] queryVector = embeddingService.embedQuery(queryText);
            SearchSession session = Search.session(entityManager);

            // 1. Search TaxonomyNode index by KNN
            List<TaxonomyNode> nodeHits = session.search(TaxonomyNode.class)
                    .where(f -> f.knn(maxResults).field("embedding").matching(queryVector))
                    .fetchHits(maxResults);

            List<TaxonomyNodeDto> matchedNodes = nodeHits.stream()
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());

            // 2. Search TaxonomyRelation index by KNN with workspace filter
            WorkspaceContext ctx = contextResolver.resolveCurrentContext();
            List<TaxonomyRelation> relationHits = session.search(TaxonomyRelation.class)
                    .where(f -> f.bool()
                            .must(f.bool()
                                    .should(f.match().field("workspaceId").matching(ctx.workspaceId()))
                                    .should(f.not(f.exists().field("workspaceId")))
                            )
                            .must(f.knn(maxResults * 2).field("embedding").matching(queryVector))
                    )
                    .fetchHits(maxResults * 2);

            // 3. Aggregate relation hits by taxonomy root (via sourceNode.taxonomyRoot)
            Map<String, Long> relationCountByRoot = relationHits.stream()
                    .filter(r -> r.getSourceNode() != null && r.getSourceNode().getTaxonomyRoot() != null)
                    .collect(Collectors.groupingBy(
                            r -> r.getSourceNode().getTaxonomyRoot(),
                            Collectors.counting()));

            // 4. Aggregate by relation type
            Map<String, Long> topRelationTypes = relationHits.stream()
                    .filter(r -> r.getRelationType() != null)
                    .collect(Collectors.groupingBy(
                            r -> r.getRelationType().name(),
                            Collectors.counting()));

            // 5. Build summary
            String summary = buildSummary(relationCountByRoot, topRelationTypes, queryText);

            log.debug("Graph search for '{}': {} node hits, {} relation hits",
                    queryText, nodeHits.size(), relationHits.size());

            return new GraphSearchResult(matchedNodes, relationCountByRoot, topRelationTypes, summary);

        } catch (Exception e) {
            log.error("Graph search failed for query '{}': {}", queryText, e.getMessage());
            return new GraphSearchResult(Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyMap(), "Graph search failed: " + e.getMessage());
        }
    }

    private String buildSummary(Map<String, Long> byRoot, Map<String, Long> byType, String query) {
        if (byRoot.isEmpty() && byType.isEmpty()) {
            return "No graph relationships matched for: " + query;
        }

        StringBuilder sb = new StringBuilder();
        if (!byRoot.isEmpty()) {
            Optional<Map.Entry<String, Long>> topRoot = byRoot.entrySet().stream()
                    .max(Map.Entry.comparingByValue());
            topRoot.ifPresent(e -> sb.append(e.getKey())
                    .append(" has the most matching relationships (")
                    .append(e.getValue()).append(")"));
        }
        if (!byType.isEmpty()) {
            Optional<Map.Entry<String, Long>> topType = byType.entrySet().stream()
                    .max(Map.Entry.comparingByValue());
            topType.ifPresent(e -> {
                if (sb.length() > 0) sb.append(". ");
                String typeName = e.getKey() != null
                        ? e.getKey().toLowerCase().replace('_', ' ') : "unknown";
                sb.append("Most common relation type: ")
                        .append(typeName)
                        .append(" (").append(e.getValue()).append(")");
            });
        }
        return sb.toString();
    }

    private TaxonomyNodeDto toFlatDto(TaxonomyNode node) {
        TaxonomyNodeDto dto = new TaxonomyNodeDto();
        dto.setId(node.getId());
        dto.setCode(node.getCode());
        dto.setUuid(node.getUuid());
        dto.setNameEn(node.getNameEn());
        dto.setNameDe(node.getNameDe());
        dto.setDescriptionEn(node.getDescriptionEn());
        dto.setDescriptionDe(node.getDescriptionDe());
        dto.setParentCode(node.getParentCode());
        dto.setTaxonomyRoot(node.getTaxonomyRoot());
        dto.setLevel(node.getLevel());
        dto.setDataset(node.getDataset());
        dto.setExternalId(node.getExternalId());
        dto.setSource(node.getSource());
        dto.setReference(node.getReference());
        dto.setSortOrder(node.getSortOrder());
        dto.setState(node.getState());
        return dto;
    }
}
