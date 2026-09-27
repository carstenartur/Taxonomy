package com.taxonomy.export.reformulation;

import com.taxonomy.diagram.DiagramModel;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Immutable, framework-free architecture evidence for a saved reformulation report. */
public record FrozenReformulationArchitecture(DiagramModel graph, Map<String, String> identity,
        List<String> gaps, List<String> warnings, boolean gapAnalysisAvailable,
        List<String> elementDetails, List<String> relationDetails, SourceIdentity source) {
    public record SourceIdentity(long projectId, long requirementId, long versionId,
            int versionNumber, String taxonomyFingerprint, String provider, String model) {}

    public FrozenReformulationArchitecture(DiagramModel graph, Map<String, String> identity,
            List<String> gaps, List<String> warnings, boolean gapAnalysisAvailable,
            List<String> elementDetails, List<String> relationDetails) {
        this(graph, identity, gaps, warnings, gapAnalysisAvailable, elementDetails, relationDetails, null);
    }
    public FrozenReformulationArchitecture {
        identity = Collections.unmodifiableMap(new LinkedHashMap<>(identity));
        gaps = List.copyOf(gaps);
        warnings = List.copyOf(warnings);
        elementDetails = List.copyOf(elementDetails);
        relationDetails = List.copyOf(relationDetails);
    }
}
