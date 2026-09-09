package com.taxonomy.archimate;

import java.util.Map;

/**
 * Represents an ArchiMate element with a type mapped from the taxonomy type.
 */
public record ArchiMateElement(
        String id,
        String label,
        String archiMateType,
        String documentation,
        Map<String, ArchiMateProperty> properties) {
    public ArchiMateElement {
        properties = Map.copyOf(properties);
    }

    public ArchiMateElement(String id, String label, String archiMateType, String documentation) {
        this(id, label, archiMateType, documentation, Map.of());
    }
}
