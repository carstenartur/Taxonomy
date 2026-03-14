package com.taxonomy.dto;

import java.util.List;

/**
 * Response payload for {@code GET /api/ai-status}.
 *
 * <p>The {@code level} field is the primary indicator of AI capability:
 * <ul>
 *   <li>{@link AiAvailabilityLevel#FULL} – cloud LLM with API key active</li>
 *   <li>{@link AiAvailabilityLevel#LIMITED} – only local ONNX embedding available</li>
 *   <li>{@link AiAvailabilityLevel#UNAVAILABLE} – no AI capabilities</li>
 * </ul>
 *
 * <p>The {@code available} boolean is kept for backward compatibility
 * ({@code true} for FULL and LIMITED, {@code false} for UNAVAILABLE).
 * The {@code limited} boolean is a convenience flag for the LIMITED state.
 */
public class AiStatusResponse {

    private final AiAvailabilityLevel level;
    private final boolean available;
    private final boolean limited;
    private final String provider;
    private final List<String> availableProviders;

    public AiStatusResponse(AiAvailabilityLevel level, String provider, List<String> availableProviders) {
        this.level              = level;
        this.available          = level != AiAvailabilityLevel.UNAVAILABLE;
        this.limited            = level == AiAvailabilityLevel.LIMITED;
        this.provider           = provider;
        this.availableProviders = availableProviders;
    }

    public AiAvailabilityLevel getLevel() {
        return level;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isLimited() {
        return limited;
    }

    public String getProvider() {
        return provider;
    }

    public List<String> getAvailableProviders() {
        return availableProviders;
    }
}
