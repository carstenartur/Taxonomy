package com.taxonomy.diagram;

import java.util.List;
import java.util.Map;

/** Checkpoint fields carried separately from the geometry and from requirement analysis scores. */
public record DiagramCanonicalEvidence(String viewId, List<AnalysisSource> analysisSources,
                                       Map<String, Relationship> relationships) {
    public DiagramCanonicalEvidence {
        analysisSources = List.copyOf(analysisSources);
        relationships = Map.copyOf(relationships);
    }

    public record AnalysisSource(String viewId, String snapshotId, String status) { }

    public record Relationship(String status, String provenance, Double confidence) { }
}
