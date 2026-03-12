package com.nato.taxonomy.dsl.model;

import java.util.*;

/**
 * Canonical representation of a requirement-to-element mapping.
 *
 * <p>Corresponds to a DSL {@code mapping} block, e.g.
 * {@code mapping REQ-001 -> CP-1001}.
 */
public class RequirementMapping {

    private String requirementId;
    private String elementId;
    private Double score;
    private String source;
    private Map<String, String> extensions = new LinkedHashMap<>();

    public RequirementMapping() {}

    public RequirementMapping(String requirementId, String elementId) {
        this.requirementId = requirementId;
        this.elementId = elementId;
    }

    public String getRequirementId() { return requirementId; }
    public void setRequirementId(String requirementId) { this.requirementId = requirementId; }

    public String getElementId() { return elementId; }
    public void setElementId(String elementId) { this.elementId = elementId; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Map<String, String> getExtensions() { return extensions; }
    public void setExtensions(Map<String, String> extensions) { this.extensions = extensions; }
}
