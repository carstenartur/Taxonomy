package com.taxonomy.dto;

import com.taxonomy.model.LinkType;

/**
 * Connects a requirement to one or more source materials.
 */
public class RequirementSourceLinkDto {

    private Long id;
    private String requirementId;
    private Long sourceArtifactId;
    private Long sourceVersionId;
    private Long sourceFragmentId;
    private LinkType linkType;
    private Double confidence;
    private String note;

    // Derived display fields (populated by the service layer)
    private String sourceTitle;
    private String sourceTypeName;

    public RequirementSourceLinkDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public Long getSourceArtifactId() { return sourceArtifactId; }
    public void setSourceArtifactId(Long sourceArtifactId) { this.sourceArtifactId = sourceArtifactId; }

    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }

    public Long getSourceFragmentId() { return sourceFragmentId; }
    public void setSourceFragmentId(Long sourceFragmentId) { this.sourceFragmentId = sourceFragmentId; }

    public LinkType getLinkType() { return linkType; }
    public void setLinkType(LinkType linkType) { this.linkType = linkType; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getSourceTitle() { return sourceTitle; }
    public void setSourceTitle(String sourceTitle) { this.sourceTitle = sourceTitle; }

    public String getSourceTypeName() { return sourceTypeName; }
    public void setSourceTypeName(String sourceTypeName) { this.sourceTypeName = sourceTypeName; }
}
