package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a concrete version/snapshot of a source artifact.
 *
 * <p>Corresponds to a DSL {@code sourceVersion} block.
 */
public class ArchitectureSourceVersion {

    private String id;
    private String sourceId;
    private String versionLabel;
    private String retrievedAt;
    private String effectiveDate;
    private String mimeType;
    private String contentHash;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureSourceVersion() {}

    public ArchitectureSourceVersion(String id, String sourceId) {
        this.id = id;
        this.sourceId = sourceId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }

    public String getRetrievedAt() { return retrievedAt; }
    public void setRetrievedAt(String retrievedAt) { this.retrievedAt = retrievedAt; }

    public String getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(String effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
