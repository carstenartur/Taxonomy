package com.taxonomy.diagram;

import com.taxonomy.dto.RelationOrigin;
import com.taxonomy.dto.RelationSearchModel;
import com.taxonomy.model.SeedType;
import java.util.List;

/** Recorded relationship provenance. No field asserts human approval. Null means unrecorded. */
public record DiagramRelationMetadata(Long relationId, RelationOrigin origin, Double confidence,
        String includedBecause, String derivationReason, String presenceReason,
        SeedType seedType, List<RelationSearchModel.Edge> requirementEvidence) {
    public DiagramRelationMetadata {
        requirementEvidence = requirementEvidence == null ? List.of() : List.copyOf(requirementEvidence);
    }
    public boolean proposed() {
        return origin == RelationOrigin.LLM_SUPPORTED || origin == RelationOrigin.SUGGESTED_CANDIDATE
                || !requirementEvidence.isEmpty()
                || "provisional (AI-suggested, not yet confirmed)".equals(includedBecause);
    }
}
