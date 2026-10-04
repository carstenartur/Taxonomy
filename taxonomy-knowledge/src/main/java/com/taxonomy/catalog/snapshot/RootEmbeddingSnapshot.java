package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.service.EmbeddingModelIdentity;

import java.util.Map;
import java.util.Objects;

/** Frozen, authorized scalar text only. Workers never reconstruct it from mutable relation projections. */
public record RootEmbeddingSnapshot(int schemaVersion, CatalogueSourceIdentity source, String rootCode,
                                     String relationCommit, String textVersion, EmbeddingModelIdentity model,
                                     Map<String, String> nodeTexts) {
    public static final int SCHEMA_VERSION = 1;
    public static final String TEXT_VERSION = "node-binder:relations-source-type-target:v1";

    public RootEmbeddingSnapshot {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported embedding snapshot schema");
        Objects.requireNonNull(source, "source");
        CatalogueRoot.require(rootCode);
        if (relationCommit == null || !relationCommit.equals(source.sourceCommit())) {
            throw new IllegalArgumentException("Embedding relations do not match the exact source commit");
        }
        if (!TEXT_VERSION.equals(textVersion)) throw new IllegalArgumentException("Unsupported embedding text version");
        Objects.requireNonNull(model, "model");
        nodeTexts = Map.copyOf(nodeTexts);
        if (nodeTexts.isEmpty() || nodeTexts.entrySet().stream()
                .anyMatch(entry -> entry.getKey().isBlank() || entry.getValue().isBlank())) {
            throw new IllegalArgumentException("Complete frozen embedding texts are required");
        }
    }
}
