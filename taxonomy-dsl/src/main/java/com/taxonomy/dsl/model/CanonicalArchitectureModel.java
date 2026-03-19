package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Complete canonical architecture model parsed from one or more DSL documents.
 *
 * <p>Aggregates all architecture elements, relations, requirements, mappings,
 * views, and evidence into a single queryable model.
 */
public class CanonicalArchitectureModel {

    private final List<ArchitectureElement> elements = new ArrayList<>();
    private final List<ArchitectureRelation> relations = new ArrayList<>();
    private final List<ArchitectureRequirement> requirements = new ArrayList<>();
    private final List<RequirementMapping> mappings = new ArrayList<>();
    private final List<ArchitectureView> views = new ArrayList<>();
    private final List<ArchitectureEvidence> evidence = new ArrayList<>();
    private final List<ArchitectureSource> sources = new ArrayList<>();
    private final List<ArchitectureSourceVersion> sourceVersions = new ArrayList<>();
    private final List<ArchitectureSourceFragment> sourceFragments = new ArrayList<>();
    private final List<ArchitectureRequirementSourceLink> requirementSourceLinks = new ArrayList<>();

    public List<ArchitectureElement> getElements() { return elements; }
    public List<ArchitectureRelation> getRelations() { return relations; }
    public List<ArchitectureRequirement> getRequirements() { return requirements; }
    public List<RequirementMapping> getMappings() { return mappings; }
    public List<ArchitectureView> getViews() { return views; }
    public List<ArchitectureEvidence> getEvidence() { return evidence; }
    public List<ArchitectureSource> getSources() { return sources; }
    public List<ArchitectureSourceVersion> getSourceVersions() { return sourceVersions; }
    public List<ArchitectureSourceFragment> getSourceFragments() { return sourceFragments; }
    public List<ArchitectureRequirementSourceLink> getRequirementSourceLinks() { return requirementSourceLinks; }

    /** Look up an element by ID. */
    public Optional<ArchitectureElement> findElement(String id) {
        return elements.stream().filter(e -> e.getId().equals(id)).findFirst();
    }

    /** Collect all unique element IDs. */
    public Set<String> allElementIds() {
        Set<String> ids = new LinkedHashSet<>();
        elements.forEach(e -> ids.add(e.getId()));
        return ids;
    }
}
