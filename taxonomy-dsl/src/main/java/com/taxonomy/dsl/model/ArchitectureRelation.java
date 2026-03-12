package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a relation between two architecture elements.
 *
 * <p>Corresponds to a DSL {@code relation} block.
 */
public class ArchitectureRelation {

    private String sourceId;
    private String relationType;
    private String targetId;
    private String status;
    private Double confidence;
    private String provenance;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureRelation() {}

    public ArchitectureRelation(String sourceId, String relationType, String targetId) {
        this.sourceId = sourceId;
        this.relationType = relationType;
        this.targetId = targetId;
    }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
