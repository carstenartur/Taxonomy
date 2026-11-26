package com.taxonomy.analysis.service;

/** Read-only runtime settings; callers capture grouped settings before admitting work. */
@FunctionalInterface
public interface AnalysisRuntimeSettings {
    int getInt(String key, int defaultValue);
    default String getString(String key, String defaultValue) { return defaultValue; }
    default boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }

    static int businessTextLimit(AnalysisRuntimeSettings settings) {
        int configured = settings == null ? 5_000 : settings.getInt("limits.max-business-text", 5_000);
        return Math.max(100, Math.min(100_000, configured));
    }

    /** Mutable adapters override this to return one immutable committed version. */
    default AnalysisRuntimeSettings snapshot() { return this; }
}
