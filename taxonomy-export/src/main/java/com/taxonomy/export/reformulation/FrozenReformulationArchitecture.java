package com.taxonomy.export.reformulation;

import com.taxonomy.diagram.DiagramModel;
import java.util.List;
import java.util.Map;

/** Immutable, framework-free architecture evidence for a saved reformulation report. */
public record FrozenReformulationArchitecture(DiagramModel graph, Map<String, String> identity,
        List<String> gaps, List<String> warnings, boolean gapAnalysisAvailable,
        List<String> elementDetails, List<String> relationDetails) {
    public FrozenReformulationArchitecture {
        identity = Map.copyOf(identity);
        gaps = List.copyOf(gaps);
        warnings = List.copyOf(warnings);
        elementDetails = List.copyOf(elementDetails);
        relationDetails = List.copyOf(relationDetails);
    }
}
