package com.taxonomy.dto;

public class RequirementElementView {

    private String nodeCode;
    private String title;
    private String taxonomySheet;
    private double relevance;
    private int hopDistance;
    private boolean anchor;
    private String includedBecause;
    /** Full hierarchy path from root to this node (e.g. "CP &gt; CP-1000 &gt; CP-1023"). */
    private String hierarchyPath;

    // ── Phase 1.3 fields ────────────────────────────────────────────────────

    /** Structured origin classification replacing free-text {@link #includedBecause}. */
    private NodeOrigin origin;

    /** Depth (level) of this node in the taxonomy tree (0 = root). */
    private int taxonomyDepth;

    /** Computed specificity value; higher means more concrete. */
    private double specificityScore;

    /** Human-readable scoring path, e.g. "CP(92%) > CP-1000(90%) > CP-1023(85%)". */
    private String scoringPath;

    /** Original LLM score (0–100); 0 when the node was only reached by propagation. */
    private int directLlmScore;

    /** Whether this node was selected for the final impact presentation. */
    private boolean selectedForImpact;

    /**
     * Nearest ancestor node code present in the same view (e.g. "CP-1000" for "CP-1023").
     * {@code null} for top-level nodes without a parent in the view.
     * Used by the display layer for containment/cluster rendering.
     */
    private String parentNodeCode;

    /**
     * Short human-readable sentence explaining why this node is present.
     * Combines origin, score, and taxonomy context into a single inspectable string.
     */
    private String presenceReason;

    public RequirementElementView() {}

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTaxonomySheet() { return taxonomySheet; }
    public void setTaxonomySheet(String taxonomySheet) { this.taxonomySheet = taxonomySheet; }

    public double getRelevance() { return relevance; }
    public void setRelevance(double relevance) { this.relevance = relevance; }

    public int getHopDistance() { return hopDistance; }
    public void setHopDistance(int hopDistance) { this.hopDistance = hopDistance; }

    public boolean isAnchor() { return anchor; }
    public void setAnchor(boolean anchor) { this.anchor = anchor; }

    public String getIncludedBecause() { return includedBecause; }
    public void setIncludedBecause(String includedBecause) { this.includedBecause = includedBecause; }

    public String getHierarchyPath() { return hierarchyPath; }
    public void setHierarchyPath(String hierarchyPath) { this.hierarchyPath = hierarchyPath; }

    // ── Phase 1.3 accessors ─────────────────────────────────────────────────

    public NodeOrigin getOrigin() { return origin; }
    public void setOrigin(NodeOrigin origin) { this.origin = origin; }

    public int getTaxonomyDepth() { return taxonomyDepth; }
    public void setTaxonomyDepth(int taxonomyDepth) { this.taxonomyDepth = taxonomyDepth; }

    public double getSpecificityScore() { return specificityScore; }
    public void setSpecificityScore(double specificityScore) { this.specificityScore = specificityScore; }

    public String getScoringPath() { return scoringPath; }
    public void setScoringPath(String scoringPath) { this.scoringPath = scoringPath; }

    public int getDirectLlmScore() { return directLlmScore; }
    public void setDirectLlmScore(int directLlmScore) { this.directLlmScore = directLlmScore; }

    public boolean isSelectedForImpact() { return selectedForImpact; }
    public void setSelectedForImpact(boolean selectedForImpact) { this.selectedForImpact = selectedForImpact; }

    public String getParentNodeCode() { return parentNodeCode; }
    public void setParentNodeCode(String parentNodeCode) { this.parentNodeCode = parentNodeCode; }

    public String getPresenceReason() { return presenceReason; }
    public void setPresenceReason(String presenceReason) { this.presenceReason = presenceReason; }
}
