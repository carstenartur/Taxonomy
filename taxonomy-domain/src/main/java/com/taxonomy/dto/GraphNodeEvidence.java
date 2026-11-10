package com.taxonomy.dto;
/** Labels and membership distinguish exact DSL declarations from request anchors/endpoints. */
public record GraphNodeEvidence(String source, String commitId, boolean presentInSelectedModel) { }
