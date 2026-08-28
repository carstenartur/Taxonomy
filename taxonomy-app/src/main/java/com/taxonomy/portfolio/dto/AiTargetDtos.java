package com.taxonomy.portfolio.dto;

import java.util.List;

/** Public, credential-free descriptions of AI targets that Taxonomy can address. */
public final class AiTargetDtos {

    private AiTargetDtos() {
    }

    public enum AiTargetMode {
        REMOTE,
        LOCAL,
        MOCK,
        REPLAY
    }

    public enum AiTargetHealth {
        READY,
        UNAVAILABLE
    }

    /** Conservative input limits applied before provider dispatch. */
    public record PromptBudget(
            int maxInputCharacters,
            int maxInputBytes,
            int estimatedMaxInputTokens) {
    }

    /**
     * Stable, non-secret identity of one configured model endpoint.
     *
     * <p>The configuration fingerprint identifies endpoint/model/budget changes
     * without exposing credentials or the custom endpoint URL.</p>
     */
    public record AiTargetDescriptor(
            String targetId,
            String displayName,
            String provider,
            String model,
            AiTargetMode mode,
            AiTargetHealth health,
            boolean available,
            boolean providerStreamingSupported,
            boolean providerCancellationSupported,
            PromptBudget promptBudget,
            String configurationFingerprint,
            String unavailableReason) {
    }

    public record AiTargetCatalogView(
            AiTargetDescriptor activeTarget,
            List<AiTargetDescriptor> targets) {
    }
}
