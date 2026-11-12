package com.taxonomy.catalog.service;

import com.taxonomy.model.RelationType;

/** Shared input for global relations and exact projected relations lacking a legacy description. */
public final class RelationEmbeddingText {
    private RelationEmbeddingText() { }

    public static String build(String sourceName, RelationType type, String targetName, String description) {
        String name = type == null ? "" : type.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        StringBuilder text = new StringBuilder(sourceName == null ? "" : sourceName)
                .append(' ').append(name).append(' ').append(targetName == null ? "" : targetName);
        if (description != null && !description.isBlank()) text.append(". ").append(description);
        return text.toString().trim();
    }
}
