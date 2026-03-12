package com.nato.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of an architecture element.
 *
 * <p>Corresponds to a DSL {@code element} block. Maps to taxonomy nodes
 * and architecture components in the existing system.
 */
public class ArchitectureElement {

    private String id;
    private String type;
    private String title;
    private String description;
    private String taxonomy;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureElement() {}

    public ArchitectureElement(String id, String type, String title, String description, String taxonomy) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.description = description;
        this.taxonomy = taxonomy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTaxonomy() { return taxonomy; }
    public void setTaxonomy(String taxonomy) { this.taxonomy = taxonomy; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
