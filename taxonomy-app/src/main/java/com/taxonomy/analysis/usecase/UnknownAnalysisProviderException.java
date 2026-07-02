package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.LlmProvider;

public class UnknownAnalysisProviderException extends RuntimeException {

    private final String provider;

    public UnknownAnalysisProviderException(String provider) {
        super("Unknown provider: " + provider);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }

    public String getValidProviders() {
        return java.util.Arrays.toString(LlmProvider.values());
    }
}
