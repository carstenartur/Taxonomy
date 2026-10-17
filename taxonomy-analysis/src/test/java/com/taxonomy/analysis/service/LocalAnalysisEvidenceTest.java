package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.*;
import jakarta.persistence.EntityManager;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalAnalysisEvidenceTest {
    private static TaxonomyNode node(String code) {
        var node = new TaxonomyNode(); node.setCode(code); node.setParentCode("IP-F"); node.setTaxonomyRoot("IP");
        return node;
    }
    private LlmService llm(LocalEmbeddingService embedding, List<TaxonomyNode> products) {
        var config = new LlmProviderConfig(embedding);
        ReflectionTestUtils.setField(config, "llmProviderConfig", "LOCAL_ONNX");
        var overlay = mock(CatalogueOverlayService.class);
        products.forEach(product -> when(overlay.isProduct(product.getCode())).thenReturn(true));
        var service = new LlmService(config, mock(LlmGatewayRegistry.class), new ObjectMapper(), mock(TaxonomyService.class),
                mock(PromptTemplateService.class), embedding, null);
        ReflectionTestUtils.setField(service, "catalogueOverlayService", overlay);
        return service;
    }
    private LocalEmbeddingService embedding(boolean inferenceFailure) {
        return new LocalEmbeddingService() {
            @Override public boolean isAvailable() { return true; }
            @Override public float[] embedQuery(String text) {
                if (inferenceFailure) throw new IllegalStateException("inference failed");
                return new float[384];
            }
            @Override public String embeddingIndexKey() { return "fixture-model"; }
        };
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void inferenceAndIndexFailuresCannotBecomeAcceptedProductZeros(boolean inferenceFailure) {
        var first = node("IP-1");
        var embedding = embedding(inferenceFailure);
        var manager = mock(EntityManager.class);
        ReflectionTestUtils.setField(embedding, "entityManager", manager);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenThrow(new IllegalStateException("index failed"));
            var result = llm(embedding, List.of(first)).analyzeSingleBatchDetailed("requirement", List.of(first), 100);
            assertThat(result.getScores()).isEmpty();
            assertThat(result.getError()).contains("LOCAL_ONNX");
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failedCategoryScoringStaysUnassessedWithDiagnostics(boolean inferenceFailure) {
        var category = node("IP-C"); var embedding = embedding(inferenceFailure);
        var manager = mock(EntityManager.class); ReflectionTestUtils.setField(embedding, "entityManager", manager);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenThrow(new IllegalStateException("index failed"));
            var service = llm(embedding, List.of());
            var result = service.analyzeSingleBatchDetailed("requirement", List.of(category), 100);
            assertThat(result.getScores()).isEmpty();
            assertThat(result.getError()).contains("LOCAL_ONNX");
            var catalogue = (TaxonomyService) ReflectionTestUtils.getField(service, "taxonomyService");
            var root = node("IP"); root.setParentCode(null);
            when(catalogue.getRootNodes()).thenReturn(new java.util.ArrayList<>(List.of(root)));
            var full = service.analyzeWithBudget("requirement");
            assertThat(full.getRawScores()).isEmpty();
            assertThat(full.getStatus()).isEqualTo("PARTIAL");
            assertThat(full.getWarnings()).anyMatch(warning -> warning.contains("LOCAL_ONNX"));
        }
    }
    @Test
    void emptyIndexResultsDoNotClaimAnEvaluatedNegativeProduct() {
        var product = node("IP-1"); var embedding = embedding(false);
        var manager = mock(EntityManager.class); ReflectionTestUtils.setField(embedding, "entityManager", manager);
        var session = indexedHits(List.of(), 1);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenReturn(session);
            var result = llm(embedding, List.of(product)).analyzeSingleBatchDetailed("requirement", List.of(product), 100);
            assertThat(result.getScores()).isEmpty();
            assertThat(result.getError()).contains("LOCAL_ONNX", "IP-1");
        }
    }
    @Test
    void incompleteCategoryIndexDoesNotRedistributeTheMissingCandidatesBudget() {
        var category = node("IP-C1"); var missing = node("IP-C2"); var embedding = embedding(false);
        var manager = mock(EntityManager.class); ReflectionTestUtils.setField(embedding, "entityManager", manager);
        var session = indexedHits(List.of(List.of(category, 0.9f)), 2);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenReturn(session);
            var result = llm(embedding, List.of()).analyzeSingleBatchDetailed("requirement", List.of(category, missing), 100);
            assertThat(result.getScores()).isEmpty();
            assertThat(result.getError()).contains("LOCAL_ONNX", "IP-C2");
        }
    }
    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void missingIndexHitStaysUnassessedWhileAnActuallyComputedZeroIsRetained() {
        var zero = node("IP-1"); var missing = node("IP-2");
        var embedding = embedding(false);
        var manager = mock(EntityManager.class);
        ReflectionTestUtils.setField(embedding, "entityManager", manager);
        var session = indexedHits(List.of(List.of(zero, 0.5f)), 2);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenReturn(session);
            var result = llm(embedding, List.of(zero, missing)).analyzeSingleBatchDetailed("requirement", List.of(zero, missing), 100);
            assertThat(result.getScores()).containsEntry("IP-1", 0d).doesNotContainKey("IP-2");
            assertThat(result.getError()).contains("LOCAL_ONNX", "IP-2");
            // Search/UI callers keep their existing tolerant compatibility contract.
            assertThat(embedding.scoreNodes("requirement", List.of(zero, missing)))
                    .containsExactlyInAnyOrderEntriesOf(Map.of("IP-1", 0d, "IP-2", 0d));
        }
    }
    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void completeComputedZeroIsARealNegativeAssessment() {
        var zero = node("IP-1"); var embedding = embedding(false);
        var manager = mock(EntityManager.class); ReflectionTestUtils.setField(embedding, "entityManager", manager);
        var session = indexedHits(List.of(List.of(zero, 0.5f)), 1);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenReturn(session);
            var result = llm(embedding, List.of(zero)).analyzeSingleBatchDetailed("requirement", List.of(zero), 100);
            assertThat(result.getScores()).containsExactlyEntriesOf(Map.of("IP-1", 0d));
            assertThat(result.getError()).isNull();
        }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private SearchSession indexedHits(List<List<?>> hits, int count) {
        var session = mock(SearchSession.class);
        org.hibernate.search.engine.search.query.dsl.SearchQuerySelectStep select = mock(org.hibernate.search.engine.search.query.dsl.SearchQuerySelectStep.class);
        org.hibernate.search.engine.search.query.dsl.SearchQueryWhereStep where = mock(org.hibernate.search.engine.search.query.dsl.SearchQueryWhereStep.class);
        org.hibernate.search.engine.search.query.dsl.SearchQueryOptionsStep options = mock(org.hibernate.search.engine.search.query.dsl.SearchQueryOptionsStep.class);
        when(session.search(TaxonomyNode.class)).thenReturn(select);
        when(select.select(any(Function.class))).thenReturn(where);
        when(where.where(any(Function.class))).thenReturn(options);
        when(options.fetchHits(count)).thenReturn(hits);
        return session;
    }
}
