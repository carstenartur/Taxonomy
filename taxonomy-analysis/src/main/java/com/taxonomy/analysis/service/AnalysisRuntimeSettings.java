package com.taxonomy.analysis.service;

/** Read-only runtime settings; callers freeze semantic settings before admitting work. */
@FunctionalInterface
public interface AnalysisRuntimeSettings {
    int getInt(String key, int defaultValue);
    default String getString(String key, String defaultValue) { return defaultValue; }
}
