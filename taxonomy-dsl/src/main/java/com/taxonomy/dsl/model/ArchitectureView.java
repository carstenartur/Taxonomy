package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of an architecture view.
 *
 * <p>Corresponds to a DSL {@code view} block. A view is a named subset of
 * architecture elements displayed together.
 */
public class ArchitectureView {

    private String id;
    private String title;
    private List<String> includes = new ArrayList<>();
    private String layout;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureView() {}

    public ArchitectureView(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<String> getIncludes() { return includes; }
    public void setIncludes(List<String> includes) { this.includes = includes; }

    public String getLayout() { return layout; }
    public void setLayout(String layout) { this.layout = layout; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
