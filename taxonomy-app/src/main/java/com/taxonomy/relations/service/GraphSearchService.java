package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.shared.service.LocalEmbeddingService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Graph-semantic search with explicit workspace relation visibility. */
@Service
public class GraphSearchService {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchService.class);

    private final LocalEmbeddingService embeddingService;

    @PersistenceContext
    private EntityManager entityManager;

    public GraphSearchService(LocalEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Transactional(readOnly = true)
    public GraphSearchResult graphSearch(String queryText,
                                         int maxResults,
                                         WorkspaceContext workspaceContext) {
        if (!embeddingService.isAvailable()) {
            return new GraphSearchResult(Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyMap(), "Semantic search is not available (embedding model not loaded).");
        }

        WorkspaceContext context = workspaceContext != null
                ? workspaceContext : WorkspaceContext.SHARED;
        try {
            float[] queryVector = embeddingService.embedQuery(queryText);
            SearchSession session = Search.session(entityManager);

            List<TaxonomyNode> nodeHits = session.search(TaxonomyNode.class)
                    .where(f -> f.knn(maxResults).field("embedding").matching(queryVector))
                    .fetchHits(maxResults);
            List<TaxonomyNodeDto> matchedNodes = nodeHits.stream()
                    .map(this::toFlatDto)
                    .collect(Collectors.toList());

            List<TaxonomyRelation> relationHits = session.search(TaxonomyRelation.class)
                    .where(f -> f.bool()
                            .must(context.workspaceId() != null
                                    ? f.bool()
                                            .should(f.match().field("workspaceId")
                                                    .matching(context.workspaceId()))
                                            .should(f.not(f.exists().field("workspaceId")))
                                    : f.not(f.exists().field("workspaceId")))
                            .must(f.knn(maxResults * 2).field("embedding").matching(queryVector)))
                    .fetchHits(maxResults * 2);

            Map<String, Long> relationCountByRoot = relationHits.stream()
                    .filter(relation -> relation.getSourceNode() != null
                            && relation.getSourceNode().getTaxonomyRoot() != null)
                    .collect(Collectors.groupingBy(
                            relation -> relation.getSourceNode().getTaxonomyRoot(),
                            Collectors.counting()));
            Map<String, Long> topRelationTypes = relationHits.stream()
                    .filter(relation -> relation.getRelationType() != null)
                    .collect(Collectors.groupingBy(
                            relation -> relation.getRelationType().name(),
                            Collectors.counting()));

            String summary = buildSummary(relationCountByRoot, topRelationTypes, queryText);
            log.debug("Graph search for '{}' in workspace {}: {} node hits, {} relation hits",
                    queryText, context.workspaceId(), nodeHits.size(), relationHits.size());
            return new GraphSearchResult(matchedNodes, relationCountByRoot, topRelationTypes, summary);
        } catch (Exception | LinkageError error) {
            log.error("Graph search failed for query '{}' in workspace {}: {}",
                    queryText, context.workspaceId(), error.getMessage(), error);
            return new GraphSearchResult(Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyMap(), "Graph search failed: " + error.getMessage());
        }
    }

    private String buildSummary(Map<String, Long> byRoot, Map<String, Long> byType, String query) {
        if (byRoot.isEmpty() && byType.isEmpty()) {
            return "No graph relationships matched for: " + query;
        }
        StringBuilder summary = new StringBuilder();
        byRoot.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(entry -> summary
                .append(entry.getKey()).append(" has the most matching relationships (")
                .append(entry.getValue()).append(')'));
        Optional<Map.Entry<String, Long>> topType = byType.entrySet().stream()
                .max(Map.Entry.comparingByValue());
        topType.ifPresent(entry -> {
            if (!summary.isEmpty()) summary.append(". ");
            summary.append("Most common relation type: ")
                    .append(entry.getKey().toLowerCase().replace('_', ' '))
                    .append(" (").append(entry.getValue()).append(')');
        });
        return summary.toString();
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
