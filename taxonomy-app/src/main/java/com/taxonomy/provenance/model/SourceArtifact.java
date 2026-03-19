package com.taxonomy.provenance.model;

import com.taxonomy.model.SourceType;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Represents the logical identity of a source material from which requirements
 * may originate (e.g. a regulation document, a business request, a meeting
 * note).
 */
@Entity
@Table(name = "source_artifact")
public class SourceArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "canonical_identifier", length = 500)
    private String canonicalIdentifier;

    @Column(name = "canonical_url", length = 1000)
    private String canonicalUrl;

    @Column(name = "origin_system", length = 200)
    private String originSystem;

    @Column(length = 200)
    private String author;

    @Column(length = 2000)
    private String description;

    @Column(length = 10)
    private String language;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected SourceArtifact() {}

    public SourceArtifact(SourceType sourceType, String title) {
        this.sourceType = sourceType;
        this.title = title;
        this.createdAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    // ── Accessors ──────────────────────────────────────────────────────────────

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
