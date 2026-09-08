package com.taxonomy.archimate;

import java.util.List;
import java.util.Map;

/** Explicit authorized export data; no persistence entities or provider payloads cross this boundary. */
public record ArchiMateExportMetadata(String modelId, Map<String, ArchiMateProperty> properties,
                                     Map<String, Map<String, ArchiMateProperty>> elements,
                                     Map<String, Map<String, ArchiMateProperty>> relationships,
                                     List<ArchiMateLoss> losses) {
    public ArchiMateExportMetadata {
        properties = Map.copyOf(properties);
        elements = copy(elements);
        relationships = copy(relationships);
        losses = List.copyOf(losses);
    }

    private static Map<String, Map<String, ArchiMateProperty>> copy(Map<String, Map<String, ArchiMateProperty>> source) {
        return source.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    public ArchiMateModel apply(ArchiMateModel model) {
        var allLosses = new java.util.ArrayList<>(model.losses());
        allLosses.addAll(losses);
        return new ArchiMateModel(modelId, model.title(), model.elements().stream().map(element ->
                new ArchiMateElement(element.id(), element.label(), element.archiMateType(), element.documentation(),
                        merge(element.properties(), elements.getOrDefault(element.id(), Map.of())))).toList(),
                model.relationships().stream().map(relation -> new ArchiMateRelationship(relation.id(),
                        relation.sourceId(), relation.targetId(), relation.archiMateType(), relation.accessType(), relation.name(),
                        merge(relation.properties(), relationships.getOrDefault(relation.id(), Map.of())))).toList(),
                model.organizations(), model.views(), merge(model.properties(), properties), allLosses);
    }

    private static Map<String, ArchiMateProperty> merge(Map<String, ArchiMateProperty> canonical,
                                                      Map<String, ArchiMateProperty> metadata) {
        var result = new java.util.TreeMap<>(canonical);
        metadata.forEach((name, value) -> {
            if (!name.startsWith("taxonomy.") || name.startsWith("taxonomy.loss.") || name.equals("taxonomy.id")) {
                throw new IllegalArgumentException("Invalid export metadata name: " + name);
            }
            var previous = result.putIfAbsent(name, value);
            if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting canonical metadata: " + name);
        });
        return result;
    }
}
