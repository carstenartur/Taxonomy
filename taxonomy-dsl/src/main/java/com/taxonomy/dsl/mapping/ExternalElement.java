package com.taxonomy.dsl.mapping;

import java.util.Map;

/**
 * Generic representation of an element from an external architecture framework.
 *
 * @param id          unique identifier within the external model
 * @param type        the element type in the external framework (e.g. {@code "Capability"}, {@code "OperationalActivity"})
 * @param name        human-readable element name
 * @param description optional description text
 * @param properties  additional framework-specific properties
 */
public record ExternalElement(
        String id,
        String type,
        String name,
        String description,
        Map<String, String> properties
) {}
