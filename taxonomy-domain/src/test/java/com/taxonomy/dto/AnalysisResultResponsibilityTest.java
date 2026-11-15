package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisResultResponsibilityTest {
    private final TaxonomyResponsibilityLookup.Assignment operations =
            new TaxonomyResponsibilityLookup.Assignment("ops", "Operations", "BP");

    @Test void taxonomyOnlyResultNamesUnitsOfRelevantAssessedElements() {
        var scope = new AnalysisScope(Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY);
        var result = new AnalysisResult(Map.of("BP", 100, "BP-1000", 75, "BP-1010", 0, "IP-1000", 95), null);
        result.setAnalysisScope(scope);
        result.setModelBinding(binding(scope));

        assertNull(result.getArchitectureView());
        assertEquals(Map.of("BP-1000", operations), result.getMatchedResponsibilities());
    }

    @Test void theGraphCarriesTheSameFrozenUnitInEitherSetterOrder() {
        for (boolean bindingFirst : List.of(true, false)) {
            var graph = new RequirementArchitectureView();
            var process = new RequirementElementView();
            process.setNodeCode("BP-1000");
            graph.setIncludedElements(List.of(process));
            var result = new AnalysisResult();
            if (bindingFirst) result.setModelBinding(binding(AnalysisScope.full()));
            result.setArchitectureView(graph);
            if (!bindingFirst) result.setModelBinding(binding(AnalysisScope.full()));
            assertEquals(operations, process.getResponsibility());
        }
    }

    private AnalysisModelBinding binding(AnalysisScope scope) {
        return new AnalysisModelBinding(AnalysisModelBinding.State.MODEL_UNCOMMITTED, "repository", "workspace", "main",
                null, 1L, "WORKSPACE_REVISION", "a".repeat(64), "catalogue", "standard-v1", Set.of("BP"), scope,
                null, Map.of(), List.of(), Set.of(), null, "catalogue",
                Map.of("BP", operations, "BP-1000", operations, "BP-1010", operations));
    }
}
