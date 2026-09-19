package com.taxonomy.analysis.service;

/** Live integer-valued transport policy; no preferences storage or mutation is exposed. */
@FunctionalInterface
public interface AnalysisRuntimeSettings {
    int getInt(String key, int defaultValue);
}
