package com.taxonomy.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;

public class RequirementElementView {

    private String nodeCode;
    private String catalogueUuid;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getCatalogueUuid() { return catalogueUuid; }
    public void setCatalogueUuid(String value) { catalogueUuid = CatalogueSourceUuids.normalize(value); }

    private String title;
    private CatalogueTitles catalogueTitles;

    /** Source titles captured with the node. User-edited titles remain authoritative. */
    public record CatalogueTitles(String en, String de) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public CatalogueTitles getCatalogueTitles() { return catalogueTitles; }
    public void setCatalogueTitles(CatalogueTitles titles) { this.catalogueTitles = titles; }

    public void retainCatalogueTitles(String english, String german) {
        catalogueTitles = english == null || english.isBlank() || german == null || german.isBlank()
                ? null : new CatalogueTitles(english, german);
    }

    public String displayTitle(boolean german) {
        return german && catalogueTitles != null && title != null && title.equals(catalogueTitles.en())
                && catalogueTitles.de() != null && !catalogueTitles.de().isBlank() ? catalogueTitles.de() : title;
    }
    private String taxonomySheet;
    private Double relevance;
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
    private double directLlmScore;

    /**
     * Original typed assessment for requirement-scoped projections. Null means no
     * assessment was supplied, not a score of zero. Legacy numeric fields remain
     * compatible with existing indexes and diagram layout; this detail is authoritative.
     */
    private AnalysisScoreDetail scoreDetail;

    public AnalysisScoreDetail getScoreDetail() { return scoreDetail; }
    public void setScoreDetail(AnalysisScoreDetail scoreDetail) { this.scoreDetail = scoreDetail; }

    /** A retained numeric relevance takes precedence over the typed assessment fallback. */
    public Double recordedRelevance() {
        if (scoreDetail != null) return getRelevance();
        if (origin == NodeOrigin.RELATION_EVIDENCE || origin == NodeOrigin.REQUIREMENT_EVIDENCE) return null;
        return relevance;
    }

    /** The saved root-to-node path can establish a depth when the numeric field was omitted. */
    public Integer recordedTaxonomyDepth() {
        if (taxonomyDepth > 0) return taxonomyDepth;
        if (hierarchyPath == null || nodeCode == null) return null;
        String[] path = hierarchyPath.split(">", -1);
        if (!nodeCode.equals(path[path.length - 1].strip())) return null;
        for (String component : path) if (component.isBlank()) return null;
        return path.length - 1;
    }

    /** Effective assignment captured from the analysis model, when available. */
    private TaxonomyResponsibilityLookup.Assignment responsibility;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public TaxonomyResponsibilityLookup.Assignment getResponsibility() { return responsibility; }
    public void setResponsibility(TaxonomyResponsibilityLookup.Assignment responsibility) { this.responsibility = responsibility; }

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

    @JsonIgnore
    public double getRelevance() {
        if (relevance != null) return relevance;
        return scoreDetail == null ? 0 : scoreDetail.effectiveRelevance() / 100.0;
    }
    @JsonIgnore
    public void setRelevance(double relevance) { this.relevance = relevance; }

    /** Persist the stored value so the layout fallback cannot become recorded evidence on replay. */
    @JsonGetter("relevance")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double serializeRelevance() { return relevance; }

    @JsonSetter("relevance")
    private void deserializeRelevance(Double relevance) { this.relevance = relevance; }

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

    public double getDirectLlmScore() { return directLlmScore; }
    public void setDirectLlmScore(double directLlmScore) { this.directLlmScore = directLlmScore; }

    public boolean isSelectedForImpact() { return selectedForImpact; }
    public void setSelectedForImpact(boolean selectedForImpact) { this.selectedForImpact = selectedForImpact; }

    public String getParentNodeCode() { return parentNodeCode; }
    public void setParentNodeCode(String parentNodeCode) { this.parentNodeCode = parentNodeCode; }

    public String getPresenceReason() { return presenceReason; }
    public void setPresenceReason(String presenceReason) { this.presenceReason = presenceReason; }
}
