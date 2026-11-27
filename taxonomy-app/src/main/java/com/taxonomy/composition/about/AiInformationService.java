package com.taxonomy.composition.about;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.analysis.service.AiTargetCatalogService;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.catalog.service.EmbeddingRuntimeInformation;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.extension.api.llm.ProviderId;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/** Composes the status of local embeddings and the configured analysis target for the About dialog. */
@Service
public class AiInformationService {

    // Public model identifiers can contain an owner namespace and a tag.
    // Require ordinary name segments so URLs, absolute paths and traversal are omitted.
    private static final String MODEL_SEGMENT = "[A-Za-z0-9][A-Za-z0-9._-]*";
    private static final Pattern MODEL_ID = Pattern.compile(
            "(?:" + MODEL_SEGMENT + "/)?" + MODEL_SEGMENT + "(?::" + MODEL_SEGMENT + ")?");

    private final LocalEmbeddingService embeddings;
    private final AiTargetCatalogService analysisTargets;

    public AiInformationService(LocalEmbeddingService embeddings, AiTargetCatalogService analysisTargets) {
        this.embeddings = embeddings;
        this.analysisTargets = analysisTargets;
    }

    public Snapshot snapshot() {
        var embedding = embeddings.runtimeInformation();
        return new Snapshot(embedding, analysisInformation(embedding));
    }

    private AnalysisInformation analysisInformation(EmbeddingRuntimeInformation embedding) {
        try {
            var target = analysisTargets.activeTarget();
            var mode = executionMode(target.provider(), target.mode());
            String model = safeModelId(target.model());
            if (mode == ExecutionMode.EMBEDDED_ONNX) {
                var loaded = embedding.loadedArtifact();
                model = loaded != null && loaded.upstream() != null ? loaded.upstream().modelId() : null;
            }
            return new AnalysisInformation(target.provider(), model,
                    target.available() ? AnalysisState.CONFIGURED : AnalysisState.UNAVAILABLE, mode);
        } catch (IllegalArgumentException invalidConfiguration) {
            // Invalid provider identifiers can contain arbitrary operator input. Never return that input.
            return new AnalysisInformation(null, null, AnalysisState.INVALID_CONFIGURATION, null);
        }
    }

    private static ExecutionMode executionMode(String provider, AiTargetMode targetMode) {
        return switch (targetMode) {
            case LOCAL -> ExecutionMode.EMBEDDED_ONNX;
            case MOCK, REPLAY -> ExecutionMode.MOCK_REPLAY;
            case REMOTE -> {
                if (LlmProvider.CUSTOM_OPENAI.name().equals(provider)) yield ExecutionMode.OPERATOR_ENDPOINT;
                yield LlmProvider.builtin(new ProviderId(provider)).isPresent()
                        ? ExecutionMode.PROVIDER_HTTP : ExecutionMode.PLUGIN;
            }
        };
    }

    private static String safeModelId(String value) {
        return value != null && value.length() <= 128 && MODEL_ID.matcher(value).matches() ? value : null;
    }

    public record Snapshot(EmbeddingRuntimeInformation embedding, AnalysisInformation analysis) { }

    public record AnalysisInformation(String provider, String model, AnalysisState state, ExecutionMode mode) { }

    public enum AnalysisState { CONFIGURED, UNAVAILABLE, INVALID_CONFIGURATION }

    public enum ExecutionMode { EMBEDDED_ONNX, PROVIDER_HTTP, OPERATOR_ENDPOINT, PLUGIN, MOCK_REPLAY }
}
