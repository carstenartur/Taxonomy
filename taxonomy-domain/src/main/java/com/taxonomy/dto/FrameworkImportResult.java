package com.taxonomy.dto;

import java.util.List;
import java.util.Map;

/**
 * Result of a generic framework import operation.
 *
 * <p>Returned by the import pipeline after parsing an external model file
 * (UAF XML, APQC CSV, Structurizr DSL, etc.) and materializing it into the database.
 */
public record FrameworkImportResult(
    String profileId,
    String profileDisplayName,
    boolean success,
    int elementsTotal,
    int elementsMapped,
    int relationsTotal,
    int relationsMapped,
    int relationsCreated,
    int hypothesesCreated,
    Long documentId,
    List<String> warnings,
    List<String> unmappedTypes,
    Map<String, Integer> statistics
) {}
