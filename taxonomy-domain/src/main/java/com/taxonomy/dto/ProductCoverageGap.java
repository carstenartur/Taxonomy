package com.taxonomy.dto;

import java.util.List;

/**
 * Signals that a relevant product family was evaluated but none of its catalogued products
 * reached the configured suitability threshold.
 */
public record ProductCoverageGap(
        String productFamilyCode,
        String productFamilyName,
        int familyScore,
        List<String> candidateCodes,
        String reason) {

    public ProductCoverageGap {
        candidateCodes = candidateCodes == null ? List.of() : List.copyOf(candidateCodes);
    }
}
