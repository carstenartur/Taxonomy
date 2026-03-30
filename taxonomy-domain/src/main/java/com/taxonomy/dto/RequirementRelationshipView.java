package com.taxonomy.dto;

import com.taxonomy.model.SeedType;

public class RequirementRelationshipView {

    /** Relation is included for scoring traceability (typically root-level propagation). */
    public static final String CATEGORY_TRACE = "trace";

    /** Relation represents a concrete cross-category architecture impact (typically leaf-to-leaf). */
    public static final String CATEGORY_IMPACT = "impact";

    /** Relation originates from the seed CSV and provides structural context (root-to-root). */
    public static final String CATEGORY_SEED = "seed";

    private Long relationId;
    private String sourceCode;
    private String targetCode;
    private String relationType;
    private double propagatedRelevance;
    private int hopDistance;
    private String includedBecause;
    private String relationCategory = CATEGORY_TRACE;

    // ── Phase 1.4 fields ────────────────────────────────────────────────────

    /** Structured origin classification for this relation. */
    private RelationOrigin origin;

    /** Confidence score (0.0–1.0) for this relation. */
    private double confidence;

    /** Human-readable reason why this relation was derived. */
    private String derivationReason;

    /** Seed type if this relation originates from the seed CSV; {@code null} otherwise. */
    private SeedType seedType;

    /**
     * Short human-readable sentence explaining why this relation is present.
     * Combines origin, endpoints, and derivation context.
     */
    private String presenceReason;

    public RequirementRelationshipView() {}

    public Long getRelationId() { return relationId; }
    public void setRelationId(Long relationId) { this.relationId = relationId; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public double getPropagatedRelevance() { return propagatedRelevance; }
    public void setPropagatedRelevance(double propagatedRelevance) { this.propagatedRelevance = propagatedRelevance; }

    public int getHopDistance() { return hopDistance; }
    public void setHopDistance(int hopDistance) { this.hopDistance = hopDistance; }

    public String getIncludedBecause() { return includedBecause; }
    public void setIncludedBecause(String includedBecause) { this.includedBecause = includedBecause; }

    public String getRelationCategory() { return relationCategory; }
    public void setRelationCategory(String relationCategory) { this.relationCategory = relationCategory; }

    // ── Phase 1.4 accessors ─────────────────────────────────────────────────

    public RelationOrigin getOrigin() { return origin; }
    public void setOrigin(RelationOrigin origin) { this.origin = origin; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getDerivationReason() { return derivationReason; }
    public void setDerivationReason(String derivationReason) { this.derivationReason = derivationReason; }

    public SeedType getSeedType() { return seedType; }
    public void setSeedType(SeedType seedType) { this.seedType = seedType; }

    public String getPresenceReason() { return presenceReason; }
    public void setPresenceReason(String presenceReason) { this.presenceReason = presenceReason; }
}
