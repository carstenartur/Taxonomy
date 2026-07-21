package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.shared.service.LocalEmbeddingService;
import com.taxonomy.shared.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmServiceBranchCoverageTest {

    @Mock private LlmProviderConfig providerConfig;
    @Mock private LlmGatewayRegistry gatewayRegistry;
    @Mock private TaxonomyService taxonomyService;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private LocalEmbeddingService localEmbeddingService;
    @Mock private SavedAnalysisService savedAnalysisService;
    @Mock private LlmGateway gateway;
    @Mock private AnalysisEventCallback callback;

    private LlmService service;

    @BeforeEach
    void setUp() {
        service = new LlmService(providerConfig, gatewayRegistry, new ObjectMapper(), taxonomyService,
                promptTemplateService, localEmbeddingService, savedAnalysisService);
        lenient().when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.OPENAI);
        lenient().when(providerConfig.getActiveProviderName()).thenReturn("OpenAI");
        lenient().when(providerConfig.getApiKey(LlmProvider.OPENAI)).thenReturn("test-key");
        lenient().when(providerConfig.getAvailabilityLevel()).thenReturn(AiAvailabilityLevel.FULL);
        lenient().when(providerConfig.isAvailable()).thenReturn(true);
        lenient().when(providerConfig.getAvailableProviders()).thenReturn(List.of("OPENAI", "LOCAL_ONNX"));
        lenient().when(gatewayRegistry.getGateway(LlmProvider.OPENAI)).thenReturn(gateway);
        lenient().when(promptTemplateService.renderPrompt(any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn("rendered prompt");
        lenient().when(promptTemplateService.renderLeafJustificationPrompt(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("leaf prompt");
        lenient().when(taxonomyService.getPathToRoot(anyString())).thenReturn(List.of());
    }

    @Test
    void delegatesProviderSelectionAndExposesDiagnostics() {
        service.setRequestProvider(LlmProvider.OPENAI);
        service.clearRequestProvider();

        assertThat(service.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(service.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.FULL);
        assertThat(service.isAvailable()).isTrue();
        assertThat(service.getActiveProviderName()).isEqualTo("OpenAI");
        assertThat(service.getAvailableProviders()).containsExactly("OPENAI", "LOCAL_ONNX");

        Map<String, Object> diagnostics = service.getDiagnostics();
        assertThat(diagnostics)
                .containsEntry("provider", "OpenAI")
                .containsEntry("apiKeyConfigured", true)
                .containsEntry("apiKeyPrefix", "test****")
                .containsEntry("totalCalls", 0L);
        verify(providerConfig).setRequestProvider(LlmProvider.OPENAI);
        verify(providerConfig).clearRequestProvider();
    }

    @Test
    void detailedCallCoversNoKeyAndLocalEmbeddingAvailability() {
        TaxonomyNode node = node("A", null, "A");
        when(providerConfig.getApiKey(LlmProvider.OPENAI)).thenReturn(" ");

        LlmCallDetail noKey = service.analyzeSingleBatchDetailed("requirement", List.of(node), 100);
        assertThat(noKey.getScores()).containsEntry("A", 0);
        assertThat(noKey.getError()).contains("No API key configured");

        when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.LOCAL_ONNX);
        when(providerConfig.getActiveProviderName()).thenReturn("Local (bge-small-en-v1.5)");
        when(localEmbeddingService.isAvailable()).thenReturn(false);
        LlmCallDetail unavailable = service.analyzeSingleBatchDetailed("requirement", List.of(node), 100);
        assertThat(unavailable.getError()).contains("not available");
        assertThat(unavailable.getScores()).containsEntry("A", 0);

        when(localEmbeddingService.isAvailable()).thenReturn(true);
        when(localEmbeddingService.scoreNodes("requirement", List.of(node))).thenReturn(Map.of("A", 17));
        LlmCallDetail available = service.analyzeSingleBatchDetailed("requirement", List.of(node), 100);
        assertThat(available.getError()).isNull();
        assertThat(available.getScores()).containsEntry("A", 100);
        assertThat(available.getRawResponse()).contains("cosine similarity");
    }

    @Test
    void detailedApiCallCoversSuccessEmptyResponseInvalidResponseAndTimeout() {
        List<TaxonomyNode> nodes = List.of(node("A", "P", "R"), node("B", "P", "R"));
        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn("body");
        when(gateway.extractResponseText("body"))
                .thenReturn("{\"A\":{\"score\":70,\"reason\":\"primary\"},\"B\":30}");

        LlmCallDetail success = service.analyzeSingleBatchDetailed("requirement", nodes, 100);
        assertThat(success.getScores()).containsEntry("A", 70).containsEntry("B", 30);
        assertThat(success.getReasons()).containsEntry("A", "primary");
        assertThat(success.getPrompt()).isEqualTo("rendered prompt");
        assertThat(success.getRawResponse()).contains("primary");

        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn(null);
        LlmCallDetail emptyBody = service.analyzeSingleBatchDetailed("requirement", nodes, 100);
        assertThat(emptyBody.getError()).contains("returned no response");

        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn("body");
        when(gateway.extractResponseText("body")).thenReturn(null);
        LlmCallDetail emptyText = service.analyzeSingleBatchDetailed("requirement", nodes, 100);
        assertThat(emptyText.getError()).contains("no usable text");

        when(gateway.extractResponseText("body")).thenReturn("not-json");
        LlmCallDetail invalid = service.analyzeSingleBatchDetailed("requirement", nodes, 100);
        assertThat(invalid.getError()).contains("Failed to parse LLM response");

        when(gateway.sendHttpRequest("rendered prompt", "test-key"))
                .thenThrow(new LlmTimeoutException("provider timed out"));
        LlmCallDetail timeout = service.analyzeSingleBatchDetailed("requirement", nodes, 100);
        assertThat(timeout.getError()).isEqualTo("provider timed out");
    }

    @Test
    void simpleBatchCoversApiLocalAndFailureFallbacks() {
        TaxonomyNode node = node("A", null, "A");
        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn("body");
        when(gateway.extractResponseText("body")).thenReturn("{\"A\":100}");
        assertThat(service.analyzeSingleBatch("requirement", List.of(node), 100)).containsEntry("A", 100);

        when(gateway.extractResponseText("body")).thenReturn("bad-json");
        assertThat(service.analyzeSingleBatch("requirement", List.of(node), 100)).containsEntry("A", 0);

        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn(null);
        assertThat(service.analyzeSingleBatch("requirement", List.of(node), 100)).containsEntry("A", 0);

        when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.LOCAL_ONNX);
        when(localEmbeddingService.isAvailable()).thenReturn(false);
        assertThat(service.analyzeSingleBatch("requirement", List.of(node), 100)).containsEntry("A", 0);

        when(localEmbeddingService.isAvailable()).thenReturn(true);
        when(localEmbeddingService.scoreNodes("requirement", List.of(node))).thenReturn(Map.of("A", 9));
        assertThat(service.analyzeSingleBatch("requirement", List.of(node), 100)).containsEntry("A", 100);
    }

    @Test
    void mockDetailedCallUsesDeterministicFallbackWhenRecordingIsUnavailable() {
        when(providerConfig.isMockMode()).thenReturn(true);
        when(savedAnalysisService.loadFromClasspath(anyString())).thenThrow(new IllegalStateException("missing fixture"));
        List<TaxonomyNode> nodes = List.of(node("BP-X", "BP", "BP"), node("BP-Y", "BP", "BP"));

        LlmCallDetail detail = service.analyzeSingleBatchDetailed("A BUSINESS REQUIREMENT", nodes, 40);

        assertThat(detail.getError()).isNull();
        assertThat(detail.getPrompt()).contains("mock mode");
        assertThat(detail.getRawResponse()).contains("hardcoded mock scores");
        assertThat(detail.getScores().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(40);
        assertThat(detail.getReasons()).containsKeys("BP-X", "BP-Y");
    }

    @Test
    void analyzeWithBudgetCoversSuccessRateLimitAndOrdinaryFailure() {
        TaxonomyNode root = node("BP", null, "BP");
        root.setNameEn("Business Processes");
        TaxonomyNode child = node("BP-1", "BP", "BP");
        child.setNameEn("Child");
        when(taxonomyService.getRootNodes()).thenReturn(new ArrayList<>(List.of(root)));
        when(taxonomyService.getChildrenOf("BP")).thenReturn(List.of(child));
        when(taxonomyService.getChildrenOf("BP-1")).thenReturn(List.of());
        when(taxonomyService.getFullTree()).thenReturn(List.of());
        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn("root", "child");
        when(gateway.extractResponseText("root")).thenReturn("{\"BP\":100}");
        when(gateway.extractResponseText("child")).thenReturn("{\"BP-1\":100}");

        AnalysisResult success = service.analyzeWithBudget("requirement");
        assertThat(success.getStatus()).isEqualTo("SUCCESS");
        assertThat(success.getScores()).containsEntry("BP", 100).containsEntry("BP-1", 100);

        TaxonomyNode other = node("CP", null, "CP");
        other.setNameEn("Capabilities");
        when(taxonomyService.getRootNodes()).thenReturn(new ArrayList<>(List.of(root, other)));
        when(gateway.sendHttpRequest(anyString(), anyString())).thenThrow(new LlmRateLimitException("quota"));
        AnalysisResult rateLimited = service.analyzeWithBudget("requirement");
        assertThat(rateLimited.getStatus()).isEqualTo("PARTIAL");
        assertThat(rateLimited.getErrorMessage()).contains("Rate limit reached");

        when(taxonomyService.getRootNodes()).thenReturn(new ArrayList<>(List.of(root)));
        when(gateway.sendHttpRequest(anyString(), anyString())).thenThrow(new IllegalStateException("network"));
        AnalysisResult failed = service.analyzeWithBudget("requirement");
        assertThat(failed.getStatus()).isEqualTo("PARTIAL");
        assertThat(failed.getWarnings()).anyMatch(w -> w.contains("network"));
    }

    @Test
    void streamingAnalysisCoversRecursiveSuccessAndErrorNotification() {
        TaxonomyNode root = node("R", null, "R");
        root.setNameEn("Root");
        TaxonomyNode child = node("C", "R", "R");
        child.setNameEn("Child");
        when(taxonomyService.getRootNodes()).thenReturn(new ArrayList<>(List.of(root)));
        when(taxonomyService.getChildrenOf("R")).thenReturn(List.of(child));
        when(taxonomyService.getChildrenOf("C")).thenReturn(List.of());
        when(gateway.sendHttpRequest("rendered prompt", "test-key")).thenReturn("root", "child");
        when(gateway.extractResponseText("root")).thenReturn("{\"R\":100}");
        when(gateway.extractResponseText("child")).thenReturn("{\"C\":100}");

        service.analyzeStreaming("requirement", callback);
        verify(callback).onPhase(anyString(), anyInt());
        verify(callback).onExpanding("R", List.of("C"));
        verify(callback).onComplete(anyString(), any(), anyList(), anyList());
        verify(callback, never()).onError(anyString(), anyString(), any(), anyList(), anyList());

        when(gateway.sendHttpRequest(anyString(), anyString())).thenThrow(new IllegalStateException("stream failed"));
        service.analyzeStreaming("requirement", callback);
        verify(callback).onError(anyString(), anyString(), any(), anyList(), anyList());
    }

    @Test
    void leafJustificationCoversMockLocalNoKeySuccessEmptyAndFailure() {
        TaxonomyNode leaf = node("A", "P", "R");
        leaf.setNameEn("Leaf");
        Map<String, Integer> scores = Map.of("A", 90, "X", 80);
        Map<String, String> reasons = Map.of("A", "path reason", "X", "cross reason");

        when(providerConfig.isMockMode()).thenReturn(true);
        assertThat(service.generateLeafJustification("Secure VOICE", "A", List.of(leaf), scores, reasons))
                .contains("secure voice");

        when(providerConfig.isMockMode()).thenReturn(false);
        when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.LOCAL_ONNX);
        assertThat(service.generateLeafJustification("x", "A", List.of(leaf), scores, reasons))
                .contains("not available");

        when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.OPENAI);
        when(providerConfig.getApiKey(LlmProvider.OPENAI)).thenReturn(" ");
        assertThat(service.generateLeafJustification("x", "A", List.of(leaf), scores, reasons))
                .contains("no API key");

        when(providerConfig.getApiKey(LlmProvider.OPENAI)).thenReturn("test-key");
        when(gateway.sendHttpRequest("leaf prompt", "test-key")).thenReturn("body");
        when(gateway.extractResponseText("body")).thenReturn("  useful justification  ");
        assertThat(service.generateLeafJustification("x", "A", List.of(leaf), scores, reasons))
                .isEqualTo("useful justification");

        when(gateway.extractResponseText("body")).thenReturn(" ");
        assertThat(service.generateLeafJustification("x", "A", List.of(leaf), scores, reasons))
                .contains("did not return");

        when(gateway.sendHttpRequest("leaf prompt", "test-key")).thenThrow(new IllegalStateException("leaf failure"));
        assertThat(service.generateLeafJustification("x", "A", List.of(leaf), scores, reasons))
                .contains("leaf failure");
    }

    @Test
    void rawCallCoversEveryProviderAndExceptionOutcome() {
        when(providerConfig.isMockMode()).thenReturn(true);
        assertThat(service.callLlmRaw("prompt")).isEqualTo("[]");

        when(providerConfig.isMockMode()).thenReturn(false);
        when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.LOCAL_ONNX);
        assertThat(service.callLlmRaw("prompt")).isNull();

        when(providerConfig.getActiveProvider()).thenReturn(LlmProvider.OPENAI);
        when(providerConfig.getApiKey(LlmProvider.OPENAI)).thenReturn(" ");
        assertThat(service.callLlmRaw("prompt")).isNull();

        when(providerConfig.getApiKey(LlmProvider.OPENAI)).thenReturn("test-key");
        when(gateway.sendHttpRequest("prompt", "test-key")).thenReturn("body");
        when(gateway.extractResponseText("body")).thenReturn("answer");
        assertThat(service.callLlmRaw("prompt")).isEqualTo("answer");

        when(gateway.sendHttpRequest("prompt", "test-key")).thenReturn(null);
        assertThat(service.callLlmRaw("prompt")).isNull();

        when(gateway.sendHttpRequest("prompt", "test-key")).thenThrow(new LlmRateLimitException("quota"));
        assertThatThrownBy(() -> service.callLlmRaw("prompt")).isInstanceOf(LlmRateLimitException.class);

        when(gateway.sendHttpRequest("prompt", "test-key")).thenThrow(new LlmTimeoutException("timeout"));
        assertThatThrownBy(() -> service.callLlmRaw("prompt")).isInstanceOf(LlmTimeoutException.class);

        when(gateway.sendHttpRequest("prompt", "test-key")).thenThrow(new IllegalStateException("broken"));
        assertThat(service.callLlmRaw("prompt")).isNull();
        assertThat(service.getDiagnostics()).containsEntry("lastError", "broken");
    }

    private static TaxonomyNode node(String code, String parentCode, String root) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setDescriptionEn("Description for " + code);
        node.setParentCode(parentCode);
        node.setTaxonomyRoot(root);
        return node;
    }
}
