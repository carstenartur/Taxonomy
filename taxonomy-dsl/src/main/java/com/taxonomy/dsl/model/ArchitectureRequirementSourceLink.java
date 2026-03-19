package com.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a link between a requirement and its source material.
 *
 * <p>Corresponds to a DSL {@code requirementSourceLink} block.
 */
public class ArchitectureRequirementSourceLink {

    private String id;
    private String requirementId;
    private String sourceId;
    private String sourceVersionId;
    private String sourceFragmentId;
    private String linkType;
    private Double confidence;
    private String note;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureRequirementSourceLink() {}

    public ArchitectureRequirementSourceLink(String id, String requirementId, String sourceId) {
        this.id = id;
        this.requirementId = requirementId;
        this.sourceId = sourceId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }

    public String getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(String sourceVersionId) { this.sourceVersionId = sourceVersionId; }

    public String getSourceFragmentId() { return sourceFragmentId; }
    public void setSourceFragmentId(String sourceFragmentId) { this.sourceFragmentId = sourceFragmentId; }

    public String getLinkType() { return linkType; }
    public void setLinkType(String linkType) { this.linkType = linkType; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
