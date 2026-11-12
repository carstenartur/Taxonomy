package com.taxonomy.provenance.service;

import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

/** Runs an authenticated document analysis or returns its local result when no provider is available. */
@FunctionalInterface
public interface DocumentAnalysisExecution {
    ResponseEntity<?> execute(String kind, String fingerprint, boolean providerAvailable,
                              Supplier<?> providerWork, Supplier<?> localResult);
}
