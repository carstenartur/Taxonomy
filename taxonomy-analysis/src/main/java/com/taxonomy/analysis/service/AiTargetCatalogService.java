package com.taxonomy.analysis.service;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetCatalogView;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetHealth;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.analysis.dto.AiTargetDtos.PromptBudget;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves stable, non-secret identities for configured AI endpoints and models.
 * Credentials and raw custom endpoint URLs never leave this component.
 */
@Component
public class AiTargetCatalogService {

    private static final String MOCK_MODEL = "taxonomy-deterministic-v1";
    private static final String LOCAL_MODEL = "bge-small-en-v1.5";
    private static final String GEMINI_MODEL = "gemini-3-flash-preview";
    private static final PromptBudget DEFAULT_PROMPT_BUDGET =
            new PromptBudget(120_000, 262_144, 30_000);

    private final LlmProviderConfig providerConfig;
    private final PromptBudget promptBudget;

    public AiTargetCatalogService(LlmProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
        this.promptBudget = DEFAULT_PROMPT_BUDGET;
    }

    public AiTargetCatalogView catalog() {
        AiTargetDescriptor active = activeTarget();
        Map<String, AiTargetDescriptor> byId = new LinkedHashMap<>();
        byId.put(active.targetId(), active);

        if (providerConfig.isMockMode()) {
            AiTargetDescriptor mock = mockTarget();
            byId.put(mock.targetId(), mock);
        }
        LlmProvider activeProvider = providerConfig.getActiveProvider();
        for (LlmProvider provider : LlmProvider.values()) {
            AiTargetDescriptor descriptor = describe(provider);
            if (descriptor.available() || provider == activeProvider) {
                byId.put(descriptor.targetId(), descriptor);
            }
        }

        List<AiTargetDescriptor> targets = new ArrayList<>(byId.values());
        targets.sort(Comparator
                .comparing((AiTargetDescriptor target) ->
                        !Objects.equals(target.targetId(), active.targetId()))
                .thenComparing(AiTargetDescriptor::displayName));
        return new AiTargetCatalogView(active, List.copyOf(targets));
    }

    public AiTargetDescriptor activeTarget() {
        if (providerConfig.isMockMode()) return mockTarget();
        LlmProvider provider = providerConfig.getActiveProvider();
        if (provider == null) {
            throw new IllegalStateException("No active AI provider is configured");
        }
        return describe(provider);
    }

    public AiTargetDescriptor resolve(String targetId, String legacyProvider) {
        String requestedTarget = normalize(targetId);
        if (requestedTarget != null) {
            return catalog().targets().stream()
                    .filter(candidate -> candidate.targetId().equals(requestedTarget))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown AI target: " + requestedTarget));
        }
        String requestedProvider = normalize(legacyProvider);
        if (requestedProvider != null) {
            if ("MOCK".equalsIgnoreCase(requestedProvider)) {
                return mockTarget();
            }
            try {
                return describe(LlmProvider.valueOf(
                        requestedProvider.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Unknown AI provider: " + requestedProvider, exception);
            }
        }
        return activeTarget();
    }

    public AiTargetDescriptor describeProvider(String provider) {
        return resolve(null, provider);
    }

    private AiTargetDescriptor mockTarget() {
        boolean available = providerConfig.isMockMode();
        return descriptor(
                "MOCK",
                MOCK_MODEL,
                AiTargetMode.MOCK,
                available,
                available ? null : "MOCK is available only when llm.mock=true.",
                "mock");
    }

    private AiTargetDescriptor describe(LlmProvider provider) {
        boolean available = providerConfig.isProviderConfigured(provider);
        String reason = available ? null : providerConfig.getProviderConfigurationError(provider);
        AiTargetMode mode = provider == LlmProvider.LOCAL_ONNX
                ? AiTargetMode.LOCAL : AiTargetMode.REMOTE;
        return descriptor(
                provider.name(),
                model(provider),
                mode,
                available,
                reason,
                endpointIdentity(provider));
    }

    private AiTargetDescriptor descriptor(
            String provider,
            String model,
            AiTargetMode mode,
            boolean available,
            String unavailableReason,
            String endpointIdentity) {
        String normalizedProvider = provider.toUpperCase(Locale.ROOT);
        String safeModel = model == null || model.isBlank() ? "unspecified" : model.strip();
        String safeEndpoint = endpointIdentity == null ? "" : endpointIdentity;
        String fingerprintMaterial = String.join("\n",
                normalizedProvider,
                safeModel,
                mode.name(),
                safeEndpoint,
                Integer.toString(promptBudget.maxInputCharacters()),
                Integer.toString(promptBudget.maxInputBytes()),
                Integer.toString(promptBudget.estimatedMaxInputTokens()));
        String fingerprint = sha256(fingerprintMaterial);
        String id = normalizedProvider.toLowerCase(Locale.ROOT).replace('_', '-')
                + ":" + slug(safeModel)
                + ("CUSTOM_OPENAI".equals(normalizedProvider)
                        ? ":" + fingerprint.substring(0, 12) : "");
        String display = displayProvider(normalizedProvider) + " / " + safeModel;
        return new AiTargetDescriptor(
                id,
                display,
                normalizedProvider,
                safeModel,
                mode,
                available ? AiTargetHealth.READY : AiTargetHealth.UNAVAILABLE,
                available,
                false,
                false,
                promptBudget,
                fingerprint,
                unavailableReason);
    }

    private String model(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> GEMINI_MODEL;
            case LOCAL_ONNX -> LOCAL_MODEL;
            case OPENAI, DEEPSEEK, QWEN, LLAMA, MISTRAL, CUSTOM_OPENAI ->
                    providerConfig.getOpenAiCompatibleModel(provider);
        };
    }

    private String endpointIdentity(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> withoutTrailingEmptyGeminiKey(providerConfig.getGeminiUrl());
            case LOCAL_ONNX -> "local:" + LOCAL_MODEL;
            case OPENAI, DEEPSEEK, QWEN, LLAMA, MISTRAL, CUSTOM_OPENAI ->
                    providerConfig.getOpenAiCompatibleUrl(provider);
        };
    }

    private static String withoutTrailingEmptyGeminiKey(String url) {
        return url == null ? "" : url.replaceAll("\\?key=$", "");
    }

    private static String displayProvider(String provider) {
        return switch (provider) {
            case "MOCK" -> "Mock";
            case "GEMINI" -> "Gemini";
            case "OPENAI" -> "OpenAI";
            case "DEEPSEEK" -> "DeepSeek";
            case "QWEN" -> "Qwen";
            case "LLAMA" -> "Llama";
            case "MISTRAL" -> "Mistral";
            case "CUSTOM_OPENAI" -> "Custom OpenAI-compatible";
            case "LOCAL_ONNX" -> "Local ONNX";
            default -> provider;
        };
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String slug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "unspecified" : slug;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
