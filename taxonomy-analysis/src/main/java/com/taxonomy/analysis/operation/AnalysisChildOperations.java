package com.taxonomy.analysis.operation;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Reserves a provider-backed command before dispatch, preserving its public result type on replay. */
public interface AnalysisChildOperations {
    <T> T execute(String kind, String fingerprint, Class<T> resultType, boolean dispatchAvailable,
                  Supplier<T> result, BooleanSupplier sourceCurrent);
}
