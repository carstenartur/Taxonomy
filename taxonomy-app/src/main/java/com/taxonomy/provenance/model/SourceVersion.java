package com.taxonomy.provenance.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Represents a concrete retrieved or imported version of a
 * {@link SourceArtifact}.
 */
@Entity
@Table(name = "source_version")
public class SourceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_artifact_id", nullable = false)
    private SourceArtifact sourceArtifact;

    @Column(name = "version_label", length = 200)
    private String versionLabel;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "content_hash", length = 128)
    private String contentHash;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "storage_location", length = 1000)
    private String storageLocation;

    @Column(name = "raw_text_location", length = 1000)
    private String rawTextLocation;

    protected SourceVersion() {}

    public SourceVersion(SourceArtifact sourceArtifact) {
        this.sourceArtifact = sourceArtifact;
        this.retrievedAt = Instant.now();
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SourceArtifact getSourceArtifact() { return sourceArtifact; }
    public void setSourceArtifact(SourceArtifact sourceArtifact) { this.sourceArtifact = sourceArtifact; }

    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }

    public Instant getRetrievedAt() { return retrievedAt; }
    public void setRetrievedAt(Instant retrievedAt) { this.retrievedAt = retrievedAt; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getStorageLocation() { return storageLocation; }
    public void setStorageLocation(String storageLocation) { this.storageLocation = storageLocation; }

    public String getRawTextLocation() { return rawTextLocation; }
    public void setRawTextLocation(String rawTextLocation) { this.rawTextLocation = rawTextLocation; }
}
