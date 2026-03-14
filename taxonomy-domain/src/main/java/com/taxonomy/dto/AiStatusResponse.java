package com.taxonomy.dto;

import java.util.List;

/**
 * Response payload for {@code GET /api/ai-status}.
 */
public class AiStatusResponse {

    private final boolean available;
    private final String provider;
    private final List<String> availableProviders;

    public AiStatusResponse(boolean available, String provider, List<String> availableProviders) {
        this.available = available;
        this.provider  = provider;
        this.availableProviders = availableProviders;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getProvider() {
        return provider;
    }

    public List<String> getAvailableProviders() {
        return availableProviders;
    }
}
