package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

public class RequirementArchitectureView {

    private List<RequirementAnchor> anchors = new ArrayList<>();
    private List<RequirementElementView> includedElements = new ArrayList<>();
    private List<RequirementRelationshipView> includedRelationships = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

    public RequirementArchitectureView() {}

    public List<RequirementAnchor> getAnchors() { return anchors; }
    public void setAnchors(List<RequirementAnchor> anchors) { this.anchors = anchors; }

    public List<RequirementElementView> getIncludedElements() { return includedElements; }
    public void setIncludedElements(List<RequirementElementView> includedElements) { this.includedElements = includedElements; }

    public List<RequirementRelationshipView> getIncludedRelationships() { return includedRelationships; }
    public void setIncludedRelationships(List<RequirementRelationshipView> includedRelationships) { this.includedRelationships = includedRelationships; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
