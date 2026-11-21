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

    public record Relationship(String status, String provenance, Double confidence, List<Witness> witnesses) {
        public Relationship { witnesses = List.copyOf(witnesses); }
        public Relationship(String status, String provenance, Double confidence) {
            this(status, provenance, confidence, List.of());
        }
    }

    public record Endpoint(String id, String contribution, String quote, String condition) { }

    public record Witness(String id, String summary, String view, String snapshot, String type, String model, Double confidence,
                          String origin, String direction, String outcome, String necessity,
                          String alternative, String question, Endpoint assessed, Endpoint candidate) { }
}
