package com.taxonomy.architecture.service;

import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.relations.service.RelationProposalService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchitectureReportMetadataPortTest {
    @Test
    void reportUsesAllMetadataFieldsAndResolvesThemForEveryReport() {
        var views = mock(RequirementArchitectureViewService.class);
        var view = mock(RequirementArchitectureView.class);
        when(views.build(anyMap(), anyString(), anyInt())).thenReturn(view);
        var gaps = mock(ArchitectureGapService.class);
        when(gaps.analyze(anyMap(), anyString(), anyInt())).thenReturn(mock(GapAnalysisView.class));
        var patterns = mock(ArchitecturePatternService.class);
        var patternView = mock(PatternDetectionView.class);
        when(patternView.getMatchedPatterns()).thenReturn(List.of());
        when(patterns.detectForScores(anyMap(), anyInt())).thenReturn(patternView);
        var recommendations = mock(ArchitectureRecommendationService.class);
        var recommendation = mock(ArchitectureRecommendation.class);
        when(recommendation.getConfirmedElements()).thenReturn(List.of());
        when(recommendations.recommend(anyMap(), anyString(), anyInt())).thenReturn(recommendation);
        var projection = mock(DiagramProjectionService.class);
        var diagram = mock(DiagramModel.class);
        when(projection.project(any(RequirementArchitectureView.class), anyString())).thenReturn(diagram);
        var mermaid = mock(MermaidExportService.class);
        when(mermaid.export(diagram)).thenReturn("graph TD");
        var proposals = mock(RelationProposalService.class);
        when(proposals.getPendingProposals()).thenReturn(List.of());
        var initial = DiagramViewMetadata.fromConfig(DiagramSelectionConfig.leafOnly(), "leafOnly");
        var updated = DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace");
        var selected = new AtomicReference<>(initial);
        var service = new ArchitectureReportService(views, gaps, patterns, recommendations,
                projection, mermaid, proposals, selected::get);

        assertThat(service.generateReport(Map.of("A", 50), "business", 20).getArchitectureView()).isSameAs(view);
        verify(view).setViewTitle(initial.viewTitle());
        verify(view).setViewDescription(initial.viewDescription());
        verify(view).setContainmentEnabled(initial.containmentEnabled());
        verify(view).setActiveRules(initial.activeRules());
        clearInvocations(view);
        selected.set(updated);
        service.generateReport(Map.of("A", 50), "business", 20);
        verify(view).setViewTitle(updated.viewTitle());
        verify(view).setViewDescription(updated.viewDescription());
        verify(view).setContainmentEnabled(updated.containmentEnabled());
        verify(view).setActiveRules(updated.activeRules());
    }
}
