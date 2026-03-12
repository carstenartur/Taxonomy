package com.nato.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a requirement.
 *
 * <p>Corresponds to a DSL {@code requirement} block.
 */
public class ArchitectureRequirement {

    private String id;
    private String title;
    private String text;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureRequirement() {}

    public ArchitectureRequirement(String id, String title, String text) {
        this.id = id;
        this.title = title;
        this.text = text;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
