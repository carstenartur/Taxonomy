package com.taxonomy.analysis.service;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.snapshot.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Full scoring orchestration with real prompts/parser and only catalogue/transport boundaries replaced. */
class RootScoringFlowTest {
    private static TaxonomyNode node(String code, String parent, String root) {
        var n = new TaxonomyNode();
        n.setCode(code); n.setNameEn(code); n.setParentCode(parent); n.setTaxonomyRoot(root);
        return n;
    }

    private static final class Catalogue extends TaxonomyService {
        private final TaxonomyNode root = node("BP", null, "BP");
        private final TaxonomyNode child = node("BP-1", "BP", "BP");
        private final boolean withChild;
        Catalogue(boolean withChild) { super(null, null, null); this.withChild = withChild; }
        @Override public List<TaxonomyNode> getRootNodes() { return new ArrayList<>(List.of(root)); }
        @Override public List<TaxonomyNode> getChildrenOf(String parent) {
            return withChild && parent.equals("BP") ? List.of(child) : List.of();
        }
        @Override public Map<String, String> getAssessmentContexts(List<TaxonomyNode> nodes) { return Map.of(); }
        @Override public List<com.taxonomy.dto.TaxonomyNodeDto> getFullTree() { return List.of(); }
    }

    private static final class Provider extends LlmProviderConfig {
        private final LlmProvider provider;
        Provider(LlmProvider provider) { super(null); this.provider = provider; }
        @Override public com.taxonomy.extension.api.llm.ProviderId getActiveProviderId() { return provider.id(); }
        @Override public String getActiveProviderName() { return provider.name(); }
        @Override public String getApiKey(LlmProvider requested) { return "test-key"; }
    }

    private static final class Gateway implements LlmGateway {
        final List<String> prompts = new ArrayList<>();
        final List<String> replies;
        Gateway(String... replies) { this.replies = List.of(replies); }
        @Override public String sendHttpRequest(String prompt, String key) {
            assertEquals("test-key", key);
            prompts.add(prompt);
            return replies.get(prompts.size() - 1);
        }
        @Override public String extractResponseText(String body) { return body; }
        @Override public String providerName() { return "fake remote"; }
    }

    private static LlmService service(LlmProvider provider, boolean withChild, Gateway gateway,
                                      LocalEmbeddingService embeddings) {
        var config = new Provider(provider);
        var registry = new LlmGatewayRegistry(config, new RestTemplate(), new ObjectMapper(), null, null, null) {
            @Override public LlmGateway getGatewayById(com.taxonomy.extension.api.llm.ProviderId selected) { return gateway; }
        };
        var prompts = new PromptTemplateService();
        prompts.loadDefaults();
        return new LlmService(config, registry, new ObjectMapper(), new Catalogue(withChild),
                prompts, embeddings, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 20, 100})
    void remoteSingletonRootRetainsIndependentRelevance(int score) {
        var gateway = new Gateway("{\"BP\":{\"score\":" + score + ",\"reason\":\"root evidence\"}}");
        AnalysisResult result = service(LlmProvider.OPENAI, false, gateway, null).analyzeWithBudget("requirement");
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(score, result.getRawScores().get("BP"));
        assertEquals("root evidence", result.getReasons().get("BP"));
        assertEquals(1, gateway.prompts.size());
        assertTrue(gateway.prompts.getFirst().contains("independent relevance"));
        assertFalse(gateway.prompts.getFirst().contains("sum to 100"));
    }

    @Test
    void streamingRootRetainsIndependentTwenty() {
        var gateway = new Gateway("{\"BP\":20}");
        var callback = new Capture();
        service(LlmProvider.OPENAI, false, gateway, null).analyzeStreaming("requirement", callback);
        assertEquals("SUCCESS", callback.status);
        assertEquals(Map.of("BP", 20), callback.scores);
    }

    @Test
    void embeddingRootRetainsIndependentTwentyWithoutHttp() {
        var gateway = new Gateway();
        var embeddings = new LocalEmbeddingService() {
            @Override public boolean isAvailable() { return true; }
            @Override public Map<String, Integer> scoreNodes(String text, List<TaxonomyNode> nodes) {
                return Map.of("BP", 20);
            }
        };
        AnalysisResult result = service(LlmProvider.LOCAL_ONNX, false, gateway, embeddings)
                .analyzeWithBudget("requirement");
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(20, result.getRawScores().get("BP"));
        assertTrue(gateway.prompts.isEmpty());
    }

    @Test
    void frozenEmbeddingRootUsesOnlyFrozenCandidateVectorsAndRetainsIndependentTwenty() {
        var embeddings = new FrozenEmbeddings(false);
        var gateway = new Gateway();
        try (var ignored = frozen(embeddings)) {
            embeddings.validateFrozenModel();
            AnalysisResult result = service(LlmProvider.LOCAL_ONNX, false, gateway, embeddings)
                    .analyzeWithBudget("requirement");
            assertEquals("SUCCESS", result.getStatus());
            assertEquals(Map.of("BP", 20), result.getRawScores());
            assertEquals(List.of("query: requirement", "frozen root text"), embeddings.texts);
            assertTrue(gateway.prompts.isEmpty());
        }
    }

    @Test
    void failedFrozenInferenceRemainsAnExplicitPartialAssessmentWithNoInventedZero() {
        var embeddings = new FrozenEmbeddings(true);
        try (var ignored = frozen(embeddings)) {
            embeddings.validateFrozenModel();
            AnalysisResult result = service(LlmProvider.LOCAL_ONNX, false, new Gateway(), embeddings)
                    .analyzeWithBudget("requirement");
            assertEquals("PARTIAL", result.getStatus());
            assertTrue(result.getRawScores().isEmpty());
            assertFalse(result.getWarnings().isEmpty());
        }
    }

    private static FrozenCatalogueContext.Scope frozen(FrozenEmbeddings embeddings) {
        var source = new CatalogueSourceIdentity("repo", "workspace", "branch", "a".repeat(40));
        var input = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        var provenance = new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001",
                java.time.Instant.EPOCH, input, input, input);
        var snapshot = new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, "BP",
                List.of(RootCatalogueSnapshot.Node.capture(node("BP", null, "BP"),
                        new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false)),
                new CatalogueOverlayService.OverlayMetadata(false, "fixture", "fixture", "v1", null, 1), provenance)
                .withEmbeddings(new RootEmbeddingSnapshot(RootEmbeddingSnapshot.SCHEMA_VERSION, source, "BP",
                        source.sourceCommit(), RootEmbeddingSnapshot.TEXT_VERSION, embeddings.embeddingIdentity(),
                        Map.of("BP", "frozen root text")));
        return new CatalogueSnapshotService(null, null, new CatalogueRuntimePolicy("worker", "BP"), null)
                .bind(source, java.util.Set.of("BP"), List.of(snapshot));
    }

    private static final class FrozenEmbeddings extends LocalEmbeddingService {
        final List<String> texts = new ArrayList<>();
        final boolean fail;
        FrozenEmbeddings(boolean fail) {
            this.fail = fail;
            ReflectionTestUtils.setField(this, "embeddingEnabled", true);
            ReflectionTestUtils.setField(this, "queryPrefix", "query: ");
        }
        @Override public EmbeddingModelIdentity embeddingIdentity() {
            return new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64), Map.of(),
                    "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
        }
        @Override public float[] embed(String text) {
            if (fail) throw new IllegalStateException("Fixture model inference failed");
            texts.add(text);
            float[] vector = new float[384];
            if (text.startsWith("query:")) vector[0] = 1;
            else { vector[0] = .2f; vector[1] = (float) Math.sqrt(.96); }
            return vector;
        }
    }

    @Test
    void singletonChildStillConsumesItsParentsTwentyPointBudget() {
        var gateway = new Gateway("{\"BP\":20}", "{\"BP-1\":5}");
        AnalysisResult result = service(LlmProvider.OPENAI, true, gateway, null).analyzeWithBudget("requirement");
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(20, result.getRawScores().get("BP"));
        assertEquals(20, result.getRawScores().get("BP-1"));
        assertEquals(2, gateway.prompts.size());
    }

    @Test
    void recoveredRootQuestionUsesIndependentSemanticsAndReplaysTwenty() {
        var gateway = new Gateway("{\"BP\":20}");
        var service = service(LlmProvider.OPENAI, false, gateway, null);
        var checkpoints = new java.util.LinkedHashMap<String, AnalysisCheckpointSession.Checkpoint>();
        var store = new AnalysisCheckpointSession.Store() {
            @Override public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return checkpoints.getOrDefault(q.key(),
                        new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
            }
            @Override public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                checkpoints.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
            }
            @Override public void checkActive() { }
        };
        try (var session = new AnalysisCheckpointSession(store)) {
            assertEquals(20, service.analyzeWithBudget("requirement").getRawScores().get("BP"));
            assertEquals(20, service.analyzeWithBudget("requirement").getRawScores().get("BP"));
        }
        assertEquals(1, gateway.prompts.size(), "successful assessment must replay, not call again");
        assertEquals(1, checkpoints.size());
        var question = checkpoints.values().iterator().next().question();
        assertEquals(AnalysisCheckpointSession.digest("ROOT_RELEVANCE", "BP"), question.key());
        assertTrue(question.prompt().contains("independent relevance"));
    }

    private static final class Capture implements AnalysisEventCallback {
        String status;
        Map<String, Integer> scores;
        @Override public void onPhase(String message, int progressPercent) { }
        @Override public void onScores(Map<String, Integer> newScores, Map<String, String> reasons,
                                       String description, LlmCallDetail detail) { }
        @Override public void onExpanding(String parentCode, List<String> childCodes) { }
        @Override public void onComplete(String status, Map<String, Integer> scores, List<String> warnings,
                                         List<TaxonomyDiscrepancy> discrepancies, List<ProductCoverageGap> gaps) {
            this.status = status; this.scores = Map.copyOf(scores);
        }
        @Override public void onError(String status, String message, Map<String, Integer> scores,
                                      List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                                      List<ProductCoverageGap> gaps) {
            fail(message);
        }
    }
}
