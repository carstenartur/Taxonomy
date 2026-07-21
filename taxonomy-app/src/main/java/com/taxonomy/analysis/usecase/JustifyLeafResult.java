package com.taxonomy.analysis.usecase;

/** Stable response contract for a generated leaf-node explanation. */
public record JustifyLeafResult(String nodeCode, String justification) {

    public JustifyLeafResult {
        if (nodeCode == null || nodeCode.isBlank()) {
            throw new IllegalArgumentException("nodeCode must not be blank");
        }
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException("justification must not be blank");
        }
    }
}
