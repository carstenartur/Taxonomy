package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a source artifact identity.
 *
 * <p>Corresponds to a DSL {@code source} block. Represents the logical identity
 * of a source material (regulation, document, etc.), independent of its concrete
 * version.
 */
public class ArchitectureSource {

    private String id;
    private String sourceType;
    private String title;
    private String canonicalIdentifier;
    private String canonicalUrl;
    private String originSystem;
    private String language;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureSource() {}

    public ArchitectureSource(String id, String sourceType, String title) {
        this.id = id;
        this.sourceType = sourceType;
        this.title = title;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCanonicalIdentifier() { return canonicalIdentifier; }
    public void setCanonicalIdentifier(String canonicalIdentifier) { this.canonicalIdentifier = canonicalIdentifier; }

    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }

    public String getOriginSystem() { return originSystem; }
    public void setOriginSystem(String originSystem) { this.originSystem = originSystem; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
