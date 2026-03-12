package com.taxonomy.archimate;

import java.util.List;
import java.util.Map;

/**
 * Top-level ArchiMate model containing elements, relationships, organization groups,
 * and an optional diagram view.
 */
public record ArchiMateModel(
        String title,
        List<ArchiMateElement> elements,
        List<ArchiMateRelationship> relationships,
        Map<String, List<String>> organizations,
        ArchiMateView view) {}
