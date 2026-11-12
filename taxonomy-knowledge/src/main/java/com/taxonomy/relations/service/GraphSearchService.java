package com.taxonomy.relations.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.error.SearchUnavailableException;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Graph-semantic search gated by the exact canonical relation source. */
@Service
public class GraphSearchService {

    private static final Logger log = LoggerFactory.getLogger(GraphSearchService.class);
    private static final int MAX_NODE_RESULTS = 1_000;
    private static final int RELATIONS_PER_NODE_RESULT = 2;
    private static final int MAX_RELATION_RESULTS =
            MAX_NODE_RESULTS * RELATIONS_PER_NODE_RESULT;

    private final LocalEmbeddingService embeddingService;
    private final GraphSearchAuthorityService authority;
    private final BranchGraphVectors branchVectors;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CatalogueRuntimePolicy catalogueRuntimePolicy = CatalogueRuntimePolicy.fullCatalogue();

    public GraphSearchService(LocalEmbeddingService embeddingService,
                              GraphSearchAuthorityService authority) {
        this(embeddingService, authority, null);
    }

    @Autowired
    public GraphSearchService(LocalEmbeddingService embeddingService, GraphSearchAuthorityService authority, BranchGraphVectors branchVectors) {
        this.branchVectors = branchVectors;
        this.embeddingService = embeddingService;
        this.authority = authority;
    }

    /** Branch reads have their own complete node and relation index, independent of the global index lifecycle. */
    public Optional<GraphSearchResult> searchBranchIfPresent(String query, int maxResults, RepositoryContext context) {
        if (branchVectors == null) return Optional.empty();
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        try {
            var source = authority.inspectForBranchSelection(context);
            String sourceCommit = source.authoritativeCommitId();
            if (maxResults <= 0) return Optional.of(noResultsRequested());
            if (source.readModel() != ReadModel.PROJECTION || source.count() == 0) return Optional.empty();
            int nodeLimit = Math.min(maxResults, MAX_NODE_RESULTS);
            return Optional.of(branchVectors.search(context, sourceCommit, query, nodeLimit, safeRelationLimit(nodeLimit)));
        } catch (Exception | LinkageError error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SearchUnavailableException();
        }
    }

    @Transactional(readOnly = true)
    public GraphSearchResult graphSearch(String queryText,
                                         int maxResults,
                                         RepositoryContext context) {
        var branch = searchBranchIfPresent(queryText, maxResults, context);
        return branch.orElseGet(() -> searchGlobalSource(queryText, maxResults, context));
    }

    /** This path independently rejects every nonempty projected branch, even if the selection moves. */
    @Transactional(readOnly = true)
    public GraphSearchResult searchGlobalSource(String queryText, int maxResults, RepositoryContext context) {
        catalogueRuntimePolicy.requireGlobalIndexAllowed();
        try {
            var source = authority.inspect(context);
            if (maxResults <= 0) return noResultsRequested();
            if (!embeddingService.isAvailable()) throw new SearchUnavailableException();

            int nodeLimit = Math.min(maxResults, MAX_NODE_RESULTS);
            int relationLimit = safeRelationLimit(nodeLimit);
            float[] queryVector = embeddingService.embedQuery(queryText);
            String indexKey = embeddingService.embeddingIndexKey();
            SearchSession session = Search.session(entityManager);

            List<TaxonomyNode> nodeHits = session.search(TaxonomyNode.class)
                    .where(f -> f.knn(nodeLimit).field("embedding").matching(queryVector)
                            .filter(f.match().field("embeddingModel").matching(indexKey)))
                    .fetchHits(nodeLimit);
            List<TaxonomyNodeDto> matchedNodes = nodeHits.stream()
                    .map(GraphSearchService::toFlatDto)
                    .collect(Collectors.toList());

            List<TaxonomyRelation> relationHits = source.readModel() == ReadModel.PROJECTION
                    ? List.of() : session.search(TaxonomyRelation.class)
                    .where(f -> f.knn(relationLimit).field("embedding").matching(queryVector)
                            .filter(f.bool()
                                    .must(f.match().field("embeddingModel").matching(indexKey))
                                    .must(f.match().field("repositoryId").matching(context.repositoryId()))
                                    .must(f.not(f.exists().field("workspaceId")))))
                    .fetchHits(relationLimit);

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
            authority.verifyUnchanged(context, source);
            log.debug("Graph search completed with {} node hits and {} relation hits",
                    nodeHits.size(), relationHits.size());
            return new GraphSearchResult(matchedNodes, relationCountByRoot, topRelationTypes, summary);
        } catch (Exception | LinkageError error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Graph search failed (code=GRAPH_SEARCH_FAILED)");
            throw new SearchUnavailableException();
        }
    }

    private static GraphSearchResult noResultsRequested() {
        return new GraphSearchResult(Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), "No graph search results were requested.");
    }

    private static int safeRelationLimit(int nodeLimit) {
        long requested = (long) nodeLimit * RELATIONS_PER_NODE_RESULT;
        return (int) Math.min(requested, MAX_RELATION_RESULTS);
    }

    static String buildSummary(Map<String, Long> byRoot, Map<String, Long> byType, String query) {
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

    static TaxonomyNodeDto toFlatDto(TaxonomyNode node) {
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
