package com.taxonomy.dsl.model;

import java.util.Map;

/** Neutral organization boundary. Empty parent denotes the explicit root scope. */
public record ArchitecturePackage(String id, String title, String description,
                                  String parentId, int position, Map<String, String> extensions) {
    public ArchitecturePackage { extensions = Map.copyOf(extensions); }
}
