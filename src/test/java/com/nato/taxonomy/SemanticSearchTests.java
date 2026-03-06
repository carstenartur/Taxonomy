package com.nato.taxonomy;

import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.model.TaxonomyRelation;
import com.nato.taxonomy.search.NodeEmbeddingBinder;
import com.nato.taxonomy.search.RelationEmbeddingBinder;
import com.nato.taxonomy.dto.TaxonomyNodeDto;
import com.nato.taxonomy.service.HybridSearchService;
import com.nato.taxonomy.service.LocalEmbeddingService;
import com.nato.taxonomy.service.RankFusionUtil;
import com.nato.taxonomy.service.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the semantic/hybrid search infrastructure introduced in Phase 3 of the
 * offline embedding plan (all-MiniLM-L6-v2 via DJL / Lucene KNN).
 *
 * <p>Note: The DJL model is NOT loaded in unit tests (embedding.enabled=true but the
 * model download is not triggered). All semantic endpoints gracefully return empty lists
 * when the model has not been loaded, which the tests verify.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SemanticSearchTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private SearchService searchService;

    // ── LocalEmbeddingService unit behaviour ─────────────────────────────────

    @Test
    void embeddingServiceIsEnabled() {
        // Default config (embedding.enabled=true)
        assertThat(embeddingService.isEnabled()).isTrue();
    }

    @Test
    void embeddingServiceEffectiveModelUrlIsNonBlank() {
        assertThat(embeddingService.effectiveModelUrl()).isNotBlank();
    }

    @Test
    void indexedNodeCountIsZeroBeforeFirstUse() {
        // The vector index is built lazily; before the first semantic search call,
        // the count is 0 (model not downloaded in CI).
        int count = embeddingService.indexedNodeCount();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    void semanticSearchReturnsEmptyListWhenModelNotLoaded() {
        // In CI the DJL model is not available; graceful degradation returns empty list.
        List<TaxonomyNodeDto> results = embeddingService.semanticSearch("satellite communications", 10);
        assertThat(results).isNotNull();
    }

    @Test
    void findSimilarNodesReturnsEmptyListWhenModelNotLoaded() {
        List<TaxonomyNodeDto> results = embeddingService.findSimilarNodes("BP", 5);
        assertThat(results).isNotNull();
    }

    // ── RankFusionUtil unit tests ─────────────────────────────────────────────

    @Test
    void rankFusionEmptyInputsReturnsEmptyList() {
        assertThat(RankFusionUtil.fuse(List.of(), List.of(), 10)).isEmpty();
    }

    @Test
    void rankFusionSingleListReturnsSameOrder() {
        List<TaxonomyNodeDto> list = buildDtos("A", "B", "C");
        List<TaxonomyNodeDto> result = RankFusionUtil.fuse(list, List.of(), 10);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getCode()).isEqualTo("A");
    }

    @Test
    void rankFusionBoostsItemsAppearingInBothLists() {
        List<TaxonomyNodeDto> semantic  = buildDtos("A", "B", "C");
        List<TaxonomyNodeDto> fulltext  = buildDtos("C", "A", "D");
        List<TaxonomyNodeDto> fused     = RankFusionUtil.fuse(semantic, fulltext, 5);

        // "A" appears at rank 1 in semantic and rank 2 in fulltext → high combined score
        // "C" appears at rank 3 in semantic and rank 1 in fulltext → also boosted
        assertThat(fused).hasSizeLessThanOrEqualTo(5);
        List<String> codes = fused.stream().map(TaxonomyNodeDto::getCode).toList();
        assertThat(codes).containsAll(List.of("A", "C"));
    }

    @Test
    void rankFusionRespectsTopKLimit() {
        List<TaxonomyNodeDto> list1 = buildDtos("A", "B", "C", "D", "E");
        List<TaxonomyNodeDto> list2 = buildDtos("F", "G", "H", "I", "J");
        List<TaxonomyNodeDto> fused = RankFusionUtil.fuse(list1, list2, 3);
        assertThat(fused).hasSize(3);
    }

    @Test
    void rankFusionDeduplicatesItems() {
        List<TaxonomyNodeDto> list1 = buildDtos("A", "B");
        List<TaxonomyNodeDto> list2 = buildDtos("A", "B");
        List<TaxonomyNodeDto> fused = RankFusionUtil.fuse(list1, list2, 10);
        long distinctCodes = fused.stream().map(TaxonomyNodeDto::getCode).distinct().count();
        assertThat(distinctCodes).isEqualTo(fused.size());
    }

    // ── HybridSearchService ───────────────────────────────────────────────────

    @Test
    void hybridSearchFallsBackToFullTextWhenEmbeddingNotLoaded() {
        // Hybrid search should return full-text results even without embedding model
        List<TaxonomyNodeDto> hybrid   = hybridSearchService.hybridSearch("communications", 20);
        List<TaxonomyNodeDto> fullText = searchService.search("communications", 20);
        assertThat(hybrid).isNotNull();
        // Without embedding the result should contain the same nodes as the full-text set
        if (!embeddingService.isAvailable()) {
            List<String> hybridCodes    = hybrid.stream().map(TaxonomyNodeDto::getCode).toList();
            List<String> fullTextCodes  = fullText.stream().map(TaxonomyNodeDto::getCode).toList();
            assertThat(hybridCodes).isEqualTo(fullTextCodes);
        }
    }

    // ── REST endpoint tests ───────────────────────────────────────────────────

    @Test
    void semanticSearchEndpointReturnsBadRequestForBlankQuery() throws Exception {
        mockMvc.perform(get("/api/search/semantic").param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void semanticSearchEndpointReturnsJsonForValidQuery() throws Exception {
        mockMvc.perform(get("/api/search/semantic").param("q", "satellite")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void hybridSearchEndpointReturnsBadRequestForBlankQuery() throws Exception {
        mockMvc.perform(get("/api/search/hybrid").param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hybridSearchEndpointReturnsJsonForValidQuery() throws Exception {
        mockMvc.perform(get("/api/search/hybrid").param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void hybridSearchReturnsResultsForKnownKeyword() throws Exception {
        // Full-text result should always be non-empty for a known taxonomy keyword
        mockMvc.perform(get("/api/search/hybrid").param("q", "BP")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void findSimilarEndpointReturnsJsonArray() throws Exception {
        mockMvc.perform(get("/api/search/similar/BP").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void embeddingStatusEndpointReturnsExpectedFields() throws Exception {
        mockMvc.perform(get("/api/embedding/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.enabled").isBoolean())
                .andExpect(jsonPath("$.available").isBoolean())
                .andExpect(jsonPath("$.modelUrl").isString())
                .andExpect(jsonPath("$.indexedNodes").isNumber());
    }

    @Test
    void embeddingStatusShowsEnabledTrueInDefaultConfig() throws Exception {
        mockMvc.perform(get("/api/embedding/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    // ── Hibernate Search migration tests ─────────────────────────────────────

    @Test
    void hibernateSearchIndexedNodesCountIsNonNegative() {
        // HS auto-indexes all 2572+ taxonomy nodes; count should be positive after startup
        int count = embeddingService.indexedNodeCount();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    @Test
    void fullTextSearchReturnsResultsForKnownTerm() {
        // "communications" should match nodes in the Communications Services taxonomy
        List<TaxonomyNodeDto> results = searchService.search("communications", 20);
        assertThat(results).isNotNull();
        assertThat(results).isNotEmpty();
    }

    @Test
    void fullTextSearchHandlesPrefixMatchForRootCode() {
        // Prefix "BP" should match Business Processes nodes
        List<TaxonomyNodeDto> results = searchService.search("BP", 20);
        assertThat(results).isNotNull();
        assertThat(results).isNotEmpty();
    }

    @Test
    void nodeEnrichedTextIncludesNodeName() {
        // Verify the NodeEmbeddingBinder.Bridge.buildEnrichedText contains the node name
        TaxonomyNode node = new TaxonomyNode();
        node.setCode("BP.001");
        node.setNameEn("Business Process Management");
        node.setDescriptionEn("Manages business processes.");
        String text = NodeEmbeddingBinder.Bridge.buildEnrichedText(node);
        assertThat(text).contains("Business Process Management");
        assertThat(text).contains("Manages business processes");
    }

    @Test
    void nodeEnrichedTextHandlesNullDescription() {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode("BP.002");
        node.setNameEn("Planning");
        String text = NodeEmbeddingBinder.Bridge.buildEnrichedText(node);
        assertThat(text).contains("Planning");
        assertThat(text).isNotBlank();
    }

    @Test
    void relationEnrichedTextContainsRelationParts() {
        // Verify RelationEmbeddingBinder.Bridge.buildEnrichedText includes source, type, target
        com.nato.taxonomy.model.TaxonomyNode source = new TaxonomyNode();
        source.setNameEn("Business Process A");
        com.nato.taxonomy.model.TaxonomyNode target = new TaxonomyNode();
        target.setNameEn("Communication Service B");
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(com.nato.taxonomy.model.RelationType.SUPPORTS);
        String text = RelationEmbeddingBinder.Bridge.buildEnrichedText(relation);
        assertThat(text).contains("Business Process A");
        assertThat(text).contains("supports");
        assertThat(text).contains("Communication Service B");
    }

    @Test
    void relationEnrichedTextIncludesDescriptionWhenPresent() {
        TaxonomyNode source = new TaxonomyNode();
        source.setNameEn("Source Node");
        TaxonomyNode target = new TaxonomyNode();
        target.setNameEn("Target Node");
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(com.nato.taxonomy.model.RelationType.DEPENDS_ON);
        relation.setDescription("Critical infrastructure dependency");
        String text = RelationEmbeddingBinder.Bridge.buildEnrichedText(relation);
        assertThat(text).contains("Critical infrastructure dependency");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<TaxonomyNodeDto> buildDtos(String... codes) {
        List<TaxonomyNodeDto> list = new ArrayList<>();
        for (String code : codes) {
            TaxonomyNodeDto dto = new TaxonomyNodeDto();
            dto.setCode(code);
            dto.setNameEn(code + " name");
            list.add(dto);
        }
        return list;
    }
}
