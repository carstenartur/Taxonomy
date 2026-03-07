package com.nato.taxonomy.dto;

public record TopRejectedProposal(
    String sourceCode,
    String sourceName,
    String targetCode,
    String targetName,
    String relationType,
    double confidence,
    String rationale
) {}
