package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Structurizr DSL files into {@link ExternalElement}s and {@link ExternalRelation}s.
 *
 * <p>Supports the core Structurizr DSL syntax:
 * <pre>
 * workspace {
 *   model {
 *     user = person "User" "A user"
 *     sys = softwareSystem "My System" {
 *       webapp = container "Web App" "Main frontend" "React"
 *       api = container "API" "REST backend" "Spring Boot" {
 *         ctrl = component "Controller" "Handles requests" "Java"
 *       }
 *     }
 *     user -> sys "Uses"
 *   }
 * }
 * </pre>
 */
public class StructurizrDslParser implements ExternalParser {

    /** Pattern: identifier = elementType "name" ["description"] ["technology"] */
    private static final Pattern ELEMENT_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s*=\\s*(person|softwareSystem|softwaresystem|container|component|deploymentNode|infrastructureNode|containerInstance)\\s+\"([^\"]*)\"(?:\\s+\"([^\"]*)\")?(?:\\s+\"([^\"]*)\")?",
            Pattern.CASE_INSENSITIVE
    );

    /** Pattern: source -> target "description" ["technology"] */
    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "^\\s*(\\w+)\\s*->\\s*(\\w+)\\s+\"([^\"]*)\"(?:\\s+\"([^\"]*)\")?");

    @Override
    public String fileFormat() {
        return "dsl";
    }

    @Override
    public ParsedExternalModel parse(InputStream input) throws Exception {
        List<ExternalElement> elements = new ArrayList<>();
        List<ExternalRelation> relations = new ArrayList<>();
        Map<String, String> identifierTypes = new LinkedHashMap<>();

        // Track container context for nesting
        Deque<String> containerStack = new ArrayDeque<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                // Skip comments and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                    continue;
                }

                // Check for element definition
                Matcher elemMatcher = ELEMENT_PATTERN.matcher(trimmed);
                if (elemMatcher.find()) {
                    String id = elemMatcher.group(1);
                    String rawType = elemMatcher.group(2);
                    String name = elemMatcher.group(3);
                    String description = elemMatcher.group(4);
                    String technology = elemMatcher.group(5);

                    String normalizedType = normalizeType(rawType);
                    identifierTypes.put(id, normalizedType);

                    Map<String, String> props = new LinkedHashMap<>();
                    if (technology != null && !technology.isEmpty()) {
                        props.put("technology", technology);
                    }

                    elements.add(new ExternalElement(id, normalizedType, name, description, props));

                    // Create CONTAINS relation if inside a container
                    if (!containerStack.isEmpty()) {
                        String parentId = containerStack.peek();
                        relations.add(new ExternalRelation(parentId, id, "Contains", Map.of()));
                    }

                    // If line ends with {, push onto container stack
                    if (trimmed.endsWith("{")) {
                        containerStack.push(id);
                    }
                    continue;
                }

                // Check for relationship definition
                Matcher relMatcher = RELATION_PATTERN.matcher(trimmed);
                if (relMatcher.find()) {
                    String sourceId = relMatcher.group(1);
                    String targetId = relMatcher.group(2);
                    String description = relMatcher.group(3);
                    String technology = relMatcher.group(4);

                    // Determine relation type from description
                    String relType = inferRelationType(description);
                    Map<String, String> props = new LinkedHashMap<>();
                    if (technology != null && !technology.isEmpty()) {
                        props.put("technology", technology);
                    }
                    if (description != null && !description.isEmpty()) {
                        props.put("description", description);
                    }

                    relations.add(new ExternalRelation(sourceId, targetId, relType, props));
                    continue;
                }

                // Track braces for container context
                if (trimmed.endsWith("{") && !containerStack.isEmpty()) {
                    // Block opening that's not an element definition — could be a section like "model {" or "views {"
                    // Don't push unless it's an element
                } else if (trimmed.equals("}")) {
                    if (!containerStack.isEmpty()) {
                        containerStack.pop();
                    }
                }
            }
        }

        return new ParsedExternalModel(elements, relations);
    }

    private String normalizeType(String rawType) {
        return switch (rawType.toLowerCase(Locale.ROOT)) {
            case "person" -> "Person";
            case "softwaresystem" -> "SoftwareSystem";
            case "container" -> "Container";
            case "component" -> "Component";
            case "deploymentnode" -> "DeploymentNode";
            case "infrastructurenode" -> "InfrastructureNode";
            case "containerinstance" -> "ContainerInstance";
            default -> rawType;
        };
    }

    private String inferRelationType(String description) {
        if (description == null) return "Uses";
        String lower = description.toLowerCase(Locale.ROOT);
        if (lower.contains("depends")) return "DependsOn";
        if (lower.contains("delivers") || lower.contains("sends")) return "Delivers";
        if (lower.contains("reads") || lower.contains("fetches")) return "Uses";
        if (lower.contains("writes") || lower.contains("stores")) return "Delivers";
        return "Uses";
    }
}
