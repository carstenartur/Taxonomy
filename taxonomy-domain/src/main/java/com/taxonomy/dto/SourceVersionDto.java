package com.taxonomy.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Represents a concrete retrieved or imported version of a
 * {@link SourceArtifactDto}.
 */
public class SourceVersionDto {

    private Long id;
    private Long sourceArtifactId;
    private String versionLabel;
    private Instant retrievedAt;
    private LocalDate effectiveDate;
    private String contentHash;
    private String mimeType;
    private String storageLocation;
    private String rawTextLocation;

    public SourceVersionDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(Long sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }

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
