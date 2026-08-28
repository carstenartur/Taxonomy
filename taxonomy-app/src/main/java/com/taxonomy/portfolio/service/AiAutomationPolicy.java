package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.portfolio.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.model.AiCostPolicy;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Central, fail-closed policy for user-triggered Copilot and unattended Autopilot work.
 *
 * <p>A custom target is never assumed to be free. Autopilot requires an explicit
 * provider, an explicit enable flag and the operator declaration {@code UNMETERED}.
 * Manual Copilot remains available for any configured addressable target because
 * every run is explicitly requested.</p>
 */
@Component
public class AiAutomationPolicy {

    private static final int MIN_PASSES = 1;
    private static final int MAX_PASSES = 3;

    public static final List<String> AUTOMATIC_STEPS = List.of(
            "immutable requirement-version analysis",
            "taxonomy scoring with reasons",
            "relation-aware architecture view",
            "gap and pattern analysis",
            "architecture recommendation",
            "immutable analysis snapshot",
            "deterministic solution reuse proposals",
            "source-bound product candidates");

    public static final List<String> HUMAN_REVIEW_REQUIRED = List.of(
            "confirm or reject taxonomy and relation mappings",
            "approve organizational responsibility",
            "select a solution or product",
            "authorize procurement or implementation",
            "merge the draft architecture into an approved branch");

    private final LlmProviderConfig providerConfig;
    private final AiTargetCatalogService targetCatalog;
    private final AiCostPolicy costPolicy;
    private final AnalysisAutomationProfile copilotProfile;
    private final AnalysisAutomationProfile autopilotProfile;
    private final int copilotPasses;
    private final int autopilotPasses;
    private final int maximumArchitectureNodes;
    private final boolean autopilotEnabled;
    private final boolean runAfterRequirementSave;
    private final String autopilotProvider;
    private final boolean autopilotSolutions;
    private final boolean autopilotProducts;
    private final Duration maximumRuntime;

    public AiAutomationPolicy(
            LlmProviderConfig providerConfig,
            AiTargetCatalogService targetCatalog,
            @Value("${taxonomy.ai.cost-policy:METERED}") String costPolicy,
            @Value("${taxonomy.ai.copilot.profile:FULL}") String copilotProfile,
            @Value("${taxonomy.ai.autopilot.profile:EXHAUSTIVE}") String autopilotProfile,
            @Value("${taxonomy.ai.copilot.verification-passes:1}") int copilotPasses,
            @Value("${taxonomy.ai.autopilot.verification-passes:2}") int autopilotPasses,
            @Value("${taxonomy.ai.max-architecture-nodes:50}") int maximumArchitectureNodes,
            @Value("${taxonomy.ai.autopilot.enabled:false}") boolean autopilotEnabled,
            @Value("${taxonomy.ai.autopilot.on-requirement-save:true}") boolean runAfterRequirementSave,
            @Value("${taxonomy.ai.autopilot.provider:}") String autopilotProvider,
            @Value("${taxonomy.ai.autopilot.propose-solutions:true}") boolean autopilotSolutions,
            @Value("${taxonomy.ai.autopilot.propose-products:true}") boolean autopilotProducts,
            @Value("${taxonomy.ai.maximum-runtime-seconds:1800}") long maximumRuntimeSeconds) {
        this.providerConfig = providerConfig;
        this.targetCatalog = targetCatalog;
        this.costPolicy = enumValue(AiCostPolicy.class, costPolicy, "taxonomy.ai.cost-policy");
        this.copilotProfile = enumValue(
                AnalysisAutomationProfile.class, copilotProfile, "taxonomy.ai.copilot.profile");
        this.autopilotProfile = enumValue(
                AnalysisAutomationProfile.class, autopilotProfile, "taxonomy.ai.autopilot.profile");
        this.copilotPasses = boundedPasses(copilotPasses, "taxonomy.ai.copilot.verification-passes");
        this.autopilotPasses = boundedPasses(
                autopilotPasses, "taxonomy.ai.autopilot.verification-passes");
        this.maximumArchitectureNodes = positive(
                maximumArchitectureNodes, "taxonomy.ai.max-architecture-nodes");
        this.autopilotEnabled = autopilotEnabled;
        this.runAfterRequirementSave = runAfterRequirementSave;
        this.autopilotProvider = normalizeOptional(autopilotProvider);
        this.autopilotSolutions = autopilotSolutions;
        this.autopilotProducts = autopilotProducts;
        this.maximumRuntime = Duration.ofSeconds(Math.max(60L, maximumRuntimeSeconds));
    }

    public RunSettings manual(CopilotRunRequest request) {
        CopilotRunRequest effective = request != null
                ? request : new CopilotRunRequest(
                        null, null, null, null, null, null, null, null);
        AnalysisAutomationProfile profile = effective.profile() != null
                ? effective.profile() : copilotProfile;
        int passes = effective.verificationPasses() != null
                ? boundedPasses(effective.verificationPasses(), "verificationPasses")
                : defaultPasses(profile, copilotPasses);
        AiTargetDescriptor target = targetCatalog.resolve(
                effective.targetId(), effective.provider());
        requireTargetReady(target);
        int nodes = effective.maxArchitectureNodes() != null
                ? positive(effective.maxArchitectureNodes(), "maxArchitectureNodes")
                : maximumArchitectureNodes;
        if (nodes > maximumArchitectureNodes) {
            throw PortfolioException.validation(
                    "maxArchitectureNodes must not exceed the configured limit of "
                            + maximumArchitectureNodes);
        }
        boolean proposeSolutions = effective.proposeSolutions() != null
                ? effective.proposeSolutions() : profile != AnalysisAutomationProfile.STANDARD;
        boolean proposeProducts = effective.proposeProducts() != null
                ? effective.proposeProducts() : profile != AnalysisAutomationProfile.STANDARD;
        return new RunSettings(
                false,
                profile,
                target.provider(),
                target,
                nodes,
                passes,
                proposeSolutions,
                proposeProducts,
                effective.forceRun());
    }

    public RunSettings autopilot() {
        if (!autopilotReady()) {
            throw PortfolioException.validation(autopilotReason());
        }
        AnalysisAutomationProfile profile = autopilotProfile;
        AiTargetDescriptor target = targetCatalog.resolve(
                null, explicitAutopilotProvider());
        return new RunSettings(
                true,
                profile,
                target.provider(),
                target,
                maximumArchitectureNodes,
                defaultPasses(profile, autopilotPasses),
                autopilotSolutions && profile != AnalysisAutomationProfile.STANDARD,
                autopilotProducts && profile != AnalysisAutomationProfile.STANDARD,
                false);
    }

    public AiAutomationStatus status() {
        String active = resolveProvider(null);
        boolean manualReady = isProviderReady(active);
        String autoProvider = explicitAutopilotProviderOrNull();
        boolean autoReady = autopilotReady();
        String reason;
        if (autoReady && runAfterRequirementSave) {
            reason = "Autopilot is explicitly enabled for an UNMETERED configured provider.";
        } else if (autoReady) {
            reason = "Autopilot is ready, but automatic execution after requirement saves is disabled.";
        } else if (!autopilotEnabled) {
            reason = "Autopilot is disabled. Set TAXONOMY_AI_AUTOPILOT_ENABLED=true to opt in.";
        } else if (costPolicy != AiCostPolicy.UNMETERED) {
            reason = "Autopilot requires TAXONOMY_AI_COST_POLICY=UNMETERED.";
        } else if (autoProvider == null) {
            reason = "Autopilot requires an explicit TAXONOMY_AI_AUTOPILOT_PROVIDER.";
        } else {
            reason = providerError(autoProvider);
        }
        return new AiAutomationStatus(
                costPolicy,
                manualReady,
                autopilotEnabled,
                autoReady,
                runAfterRequirementSave,
                active,
                autoProvider,
                copilotProfile,
                autopilotProfile,
                defaultPasses(copilotProfile, copilotPasses),
                defaultPasses(autopilotProfile, autopilotPasses),
                maximumArchitectureNodes,
                reason,
                AUTOMATIC_STEPS,
                HUMAN_REVIEW_REQUIRED);
    }

    public boolean autopilotReady() {
        String provider = explicitAutopilotProviderOrNull();
        return autopilotEnabled
                && costPolicy == AiCostPolicy.UNMETERED
                && provider != null
                && isProviderReady(provider);
    }

    public boolean automaticAfterRequirementSaveReady() {
        return runAfterRequirementSave && autopilotReady();
    }

    public String autopilotReason() {
        return status().reason();
    }

    public AiCostPolicy costPolicy() {
        return costPolicy;
    }

    public Duration maximumRuntime() {
        return maximumRuntime;
    }

    public AiTargetDescriptor targetForProvider(String provider) {
        return targetCatalog.describeProvider(provider);
    }

    private String resolveProvider(String requested) {
        String normalized = normalizeOptional(requested);
        if (normalized != null) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        if (providerConfig.isMockMode()) {
            return "MOCK";
        }
        return providerConfig.getActiveProvider().name();
    }

    private String explicitAutopilotProvider() {
        String provider = explicitAutopilotProviderOrNull();
        if (provider == null) {
            throw PortfolioException.validation(
                    "Autopilot requires an explicit TAXONOMY_AI_AUTOPILOT_PROVIDER.");
        }
        return provider;
    }

    private String explicitAutopilotProviderOrNull() {
        return autopilotProvider != null
                ? autopilotProvider.toUpperCase(Locale.ROOT) : null;
    }

    private boolean isProviderReady(String provider) {
        if (provider == null) {
            return false;
        }
        if ("MOCK".equals(provider)) {
            return providerConfig.isMockMode();
        }
        try {
            return providerConfig.isProviderConfigured(LlmProvider.valueOf(provider));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireTargetReady(AiTargetDescriptor target) {
        if (!target.available()) {
            throw PortfolioException.validation(target.unavailableReason() != null
                    ? target.unavailableReason()
                    : "The selected AI target is unavailable: " + target.targetId());
        }
    }

    private String providerError(String provider) {
        if (provider == null) {
            return "No AI provider was selected.";
        }
        if ("MOCK".equals(provider)) {
            return "MOCK is available only when llm.mock=true.";
        }
        try {
            String error = providerConfig.getProviderConfigurationError(
                    LlmProvider.valueOf(provider));
            return error != null ? error : "The selected provider is not ready: " + provider;
        } catch (IllegalArgumentException exception) {
            return "Unknown AI provider: " + provider;
        }
    }

    private static int defaultPasses(AnalysisAutomationProfile profile, int configured) {
        if (profile == AnalysisAutomationProfile.STANDARD) return 1;
        if (profile == AnalysisAutomationProfile.FULL) return Math.max(1, configured);
        return Math.max(2, configured);
    }

    private static int boundedPasses(int value, String field) {
        if (value < MIN_PASSES || value > MAX_PASSES) {
            throw new IllegalArgumentException(field + " must be between 1 and 3");
        }
        return value;
    }

    private static int positive(int value, String field) {
        if (value < 1) {
            throw PortfolioException.validation(field + " must be at least 1");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " has unsupported value: " + value, exception);
        }
    }

    public record RunSettings(
            boolean autopilot,
            AnalysisAutomationProfile profile,
            String provider,
            AiTargetDescriptor target,
            int maxArchitectureNodes,
            int verificationPasses,
            boolean proposeSolutions,
            boolean proposeProducts,
            boolean force) {
    }
}
