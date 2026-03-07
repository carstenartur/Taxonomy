package com.nato.taxonomy.dto;

public record ProvenanceMetrics(
    String provenance,
    int proposed,
    int accepted,
    double acceptanceRate
) {}
