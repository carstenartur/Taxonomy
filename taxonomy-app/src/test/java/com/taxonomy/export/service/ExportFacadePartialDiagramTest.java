package com.taxonomy.export.service;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.export.DiagramProjectionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportFacadePartialDiagramTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveExportRetainsPartialStatusWhenCoverageHasNoOpenCount(boolean hasCoverage) {
        LlmService analysis = mock(LlmService.class);
        RequirementArchitectureViewService architecture = mock(RequirementArchitectureViewService.class);
        var result = new AnalysisResult(Map.of("CP", 80), List.of());
        result.setStatus("PARTIAL");
        if (hasCoverage) result.setAnalysisCoverage(new AnalysisCoverage(Map.of(), 0, 0, 0));
        var element = new RequirementElementView();
        element.setNodeCode("CP"); element.setTitle("Capability"); element.setRelevance(0.8);
        element.setTaxonomySheet("CP"); element.setAnchor(true);
        var view = new RequirementArchitectureView();
        view.setIncludedElements(List.of(element));
        when(analysis.analyzeWithBudget("requirement")).thenReturn(result);
        when(architecture.build(result.getScores(), "requirement", 20)).thenReturn(view);
        var facade = new ExportFacade(analysis, architecture, new DiagramProjectionService(),
                null, null, null, null, null, null);

        var diagram = facade.buildDiagram("requirement");

        assertThat(diagram.title()).contains("PARTIAL").doesNotContain("0 unassessed");
        assertThat(diagram.nodes()).singleElement().satisfies(node -> assertThat(node.id()).isEqualTo("CP"));
        assertThat(result.getScores()).containsEntry("CP", 80);
    }
}
