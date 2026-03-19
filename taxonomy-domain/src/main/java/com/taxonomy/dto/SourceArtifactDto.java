package com.taxonomy.dto;

import com.taxonomy.model.SourceType;

import java.time.Instant;

/**
 * Represents the logical identity of a source material, independent of its
 * concrete version or type.
 */
public class SourceArtifactDto {

    private Long id;
    private SourceType sourceType;
    private String title;
    private String canonicalIdentifier;
    private String canonicalUrl;
    private String originSystem;
    private String author;
    private String description;
    private String language;
    private Instant createdAt;
    private Instant updatedAt;

    public SourceArtifactDto() {}

    public SourceArtifactDto(SourceType sourceType, String title) {
        this.sourceType = sourceType;
        this.title = title;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCanonicalIdentifier() { return canonicalIdentifier; }
    public void setCanonicalIdentifier(String canonicalIdentifier) { this.canonicalIdentifier = canonicalIdentifier; }

    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }

    public String getOriginSystem() { return originSystem; }
    public void setOriginSystem(String originSystem) { this.originSystem = originSystem; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
