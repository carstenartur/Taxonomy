package com.taxonomy.archimate;

import java.util.List;
import java.util.Map;

/**
 * Top-level ArchiMate model containing elements, relationships, organization groups,
 * and zero or more diagram views. Original identities are distinct from XML IDs.
 */
public record ArchiMateModel(
        String id,
        String title,
        List<ArchiMateElement> elements,
        List<ArchiMateRelationship> relationships,
        Map<String, List<String>> organizations,
        List<ArchiMateView> views,
        Map<String, ArchiMateProperty> properties,
        List<ArchiMateLoss> losses) {
    public ArchiMateModel {
        elements = List.copyOf(elements);
        relationships = List.copyOf(relationships);
        organizations = organizations == null ? Map.of() : organizations.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        views = List.copyOf(views);
        properties = Map.copyOf(properties);
        losses = List.copyOf(losses);
    }

    public ArchiMateModel(String title, List<ArchiMateElement> elements,
                         List<ArchiMateRelationship> relationships,
                         Map<String, List<String>> organizations, ArchiMateView view) {
        this("architecture", title, elements, relationships, organizations,
                view == null ? List.of() : List.of(view), Map.of(), List.of());
    }

    public ArchiMateView view() {
        return views.isEmpty() ? null : views.getFirst();
    }
}
