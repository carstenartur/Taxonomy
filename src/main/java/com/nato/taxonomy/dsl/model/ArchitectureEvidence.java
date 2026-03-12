package com.nato.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of evidence supporting a relation.
 *
 * <p>Corresponds to a DSL {@code evidence} block.
 */
public class ArchitectureEvidence {

    private String id;
    private String forRelationSource;
    private String forRelationType;
    private String forRelationTarget;
    private String evidenceType;
    private String model;
    private Double confidence;
    private String summary;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public ArchitectureEvidence() {}

    public ArchitectureEvidence(String id) {
        this.id = id;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getForRelationSource() { return forRelationSource; }
    public void setForRelationSource(String forRelationSource) { this.forRelationSource = forRelationSource; }

    public String getForRelationType() { return forRelationType; }
    public void setForRelationType(String forRelationType) { this.forRelationType = forRelationType; }

    public String getForRelationTarget() { return forRelationTarget; }
    public void setForRelationTarget(String forRelationTarget) { this.forRelationTarget = forRelationTarget; }

    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
