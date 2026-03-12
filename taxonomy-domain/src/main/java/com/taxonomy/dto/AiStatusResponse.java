package com.taxonomy.dto;

/**
 * Response payload for {@code GET /api/ai-status}.
 */
public class AiStatusResponse {

    private final boolean available;
    private final String provider;

    public AiStatusResponse(boolean available, String provider) {
        this.available = available;
        this.provider  = provider;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getProvider() {
        return provider;
    }
}
