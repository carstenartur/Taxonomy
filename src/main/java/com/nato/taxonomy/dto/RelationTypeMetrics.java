package com.nato.taxonomy.dto;

public record RelationTypeMetrics(
    String relationType,
    int proposed,
    int accepted,
    int rejected,
    double acceptanceRate
) {}
