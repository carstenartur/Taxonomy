package com.nato.taxonomy.dto;

import java.util.List;

public record RelationQualityMetrics(
    int totalProposals,
    int accepted,
    int rejected,
    int pending,
    double acceptanceRate,
    double avgConfidenceAccepted,
    double avgConfidenceRejected,
    List<RelationTypeMetrics> byRelationType,
    List<ProvenanceMetrics> byProvenance
) {}
