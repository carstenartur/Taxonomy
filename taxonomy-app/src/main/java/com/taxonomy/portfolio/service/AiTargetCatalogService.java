package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.portfolio.dto.AiTargetDtos.AiTargetCatalogView;
import com.taxonomy.portfolio.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.portfolio.dto.AiTargetDtos.AiTargetHealth;
import com.taxonomy.portfolio.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.portfolio.dto.AiTargetDtos.PromptBudget;
import org.springframework.beans.factory.annotation.Value;
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

    private final LlmProviderConfig providerConfig;
    private final PromptBudget promptBudget;

    public AiTargetCatalogService(
            LlmProviderConfig providerConfig,
            @Value("${taxonomy.ai.prompt.max-input-characters:120000}") int maxInputCharacters,
            @Value("${taxonomy.ai.prompt.max-input-bytes:262144}") int maxInputBytes,
            @Value("${taxonomy.ai.prompt.estimated-max-input-tokens:30000}") int estimatedMaxInputTokens) {
        this.providerConfig = providerConfig;
        this.promptBudget = new PromptBudget(
                positive(maxInputCharacters, "taxonomy.ai.prompt.max-input-characters"),
                positive(maxInputBytes, "taxonomy.ai.prompt.max-input-bytes"),
                positive(estimatedMaxInputTokens,
                        "taxonomy.ai.prompt.estimated-max-input-tokens"));
    }

    public AiTargetCatalogView catalog() {
        AiTargetDescriptor active = activeTarget();
        Map<String, AiTargetDescriptor> byId = new LinkedHashMap<>();
        byId.put(active.targetId(), active);

        if (providerConfig.isMockMode()) {
            AiTargetDescriptor mock = mockTarget();
            byId.put(mock.targetId(), mock);
        }
        for (LlmProvider provider : LlmProvider.values()) {
            AiTargetDescriptor descriptor = describe(provider);
            if (descriptor.available() || provider == providerConfig.getActiveProvider()) {
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
        return describe(providerConfig.getActiveProvider());
    }

    public AiTargetDescriptor resolve(String targetId, String legacyProvider) {
        String requestedTarget = normalize(targetId);
        if (requestedTarget != null) {
            return catalog().targets().stream()
                    .filter(candidate -> candidate.targetId().equals(requestedTarget))
                    .findFirst()
                    .orElseThrow(() -> PortfolioException.validation(
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
                throw PortfolioException.validation(
                        "Unknown AI provider: " + requestedProvider);
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
                "mock",
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
        String fingerprintMaterial = String.join("\n",
                normalizedProvider,
                model,
                mode.name(),
                endpointIdentity,
                Integer.toString(promptBudget.maxInputCharacters()),
                Integer.toString(promptBudget.maxInputBytes()),
                Integer.toString(promptBudget.estimatedMaxInputTokens()));
        String fingerprint = sha256(fingerprintMaterial);
        String id = normalizedProvider.toLowerCase(Locale.ROOT).replace('_', '-')
                + ":" + slug(model)
                + ("CUSTOM_OPENAI".equals(normalizedProvider)
                        ? ":" + fingerprint.substring(0, 12) : "");
        String display = displayProvider(normalizedProvider) + " / " + model;
        return new AiTargetDescriptor(
                id,
                display,
                normalizedProvider,
                model,
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
            case GEMINI -> providerConfig.getGeminiUrl().replaceAll("\\?key=$", "");
            case LOCAL_ONNX -> "local:" + LOCAL_MODEL;
            case OPENAI, DEEPSEEK, QWEN, LLAMA, MISTRAL, CUSTOM_OPENAI ->
                    providerConfig.getOpenAiCompatibleUrl(provider);
        };
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

    private static int positive(int value, String property) {
        if (value < 1) {
            throw new IllegalArgumentException(property + " must be at least 1");
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String slug(String value) {
        String slug = value == null ? "unspecified" : value.toLowerCase(Locale.ROOT)
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
