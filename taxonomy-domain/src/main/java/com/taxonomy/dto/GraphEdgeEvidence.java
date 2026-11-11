package com.taxonomy.dto;
/** Evidence copied from the selected commit's proven relation projection. */
public record GraphEdgeEvidence(String commitId, String causationId, String provenance,
                                String status, Double confidence) { }
