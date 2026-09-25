package com.taxonomy.export.service;

import com.taxonomy.analysis.service.AnalysisRuntimeSettings;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.ArchiMateDiagramService;
import com.taxonomy.export.DiagramProjectionService;
import com.taxonomy.export.MermaidExportService;
import com.taxonomy.export.StructurizrExportService;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.analysis.service.SavedAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportFacadePreferenceTest {

    @Test
    void analyzedExportsUseLiveArchitectureNodePreference() {
        LlmService llm = mock(LlmService.class);
        RequirementArchitectureViewService architecture = mock(RequirementArchitectureViewService.class);
        DiagramProjectionService projection = mock(DiagramProjectionService.class);
        AnalysisRuntimeSettings settings = mock(AnalysisRuntimeSettings.class);
        DiagramModel expected = mock(DiagramModel.class);

        AnalysisResult result = new AnalysisResult();
        result.setScores(Map.of("CP", 81));
        RequirementArchitectureView view = new RequirementArchitectureView();
        when(llm.analyzeWithBudget("Need resilient communications")).thenReturn(result);
        when(settings.getInt("limits.max-architecture-nodes", 50)).thenReturn(150);
        when(architecture.build(result.getScores(), "Need resilient communications", 150))
                .thenReturn(view);
        when(projection.project(any(RequirementArchitectureView.class), anyString()))
                .thenReturn(expected);

        ExportFacade facade = new ExportFacade(
                llm,
                architecture,
                projection,
                mock(VisioDiagramService.class),
                mock(VisioPackageBuilder.class),
                mock(ArchiMateDiagramService.class),
                mock(ArchiMateXmlExporter.class),
                mock(MermaidExportService.class),
                mock(StructurizrExportService.class),
                mock(SavedAnalysisService.class));
        ReflectionTestUtils.setField(facade, "analysisRuntimeSettings", settings);

        assertThat(facade.buildDiagram("Need resilient communications")).isSameAs(expected);
        verify(architecture).build(result.getScores(), "Need resilient communications", 150);
    }
}
