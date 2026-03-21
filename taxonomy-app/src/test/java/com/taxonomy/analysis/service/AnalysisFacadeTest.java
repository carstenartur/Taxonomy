package com.taxonomy.analysis.service;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.SavedAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalysisFacadeTest {

    @Mock
    private LlmService llmService;

    @Mock
    private SavedAnalysisService savedAnalysisService;

    @Mock
    private AnalysisRelationGenerator analysisRelationGenerator;

    @InjectMocks
    private AnalysisFacade analysisFacade;

    @Test
    void buildAnalysisExport_delegatesToSavedAnalysisService() {
        String requirement = "Must support multi-tenancy";
        Map<String, Integer> scores = Map.of("CP-1023", 85, "CR-1047", 60);
        Map<String, String> reasons = Map.of("CP-1023", "Directly relevant", "CR-1047", "Partially relevant");
        String provider = "gemini";

        SavedAnalysis expected = new SavedAnalysis();
        expected.setRequirement(requirement);
        when(savedAnalysisService.buildExport(requirement, scores, reasons, provider)).thenReturn(expected);

        SavedAnalysis result = analysisFacade.buildAnalysisExport(requirement, scores, reasons, provider);

        assertThat(result).isSameAs(expected);
        verify(savedAnalysisService).buildExport(requirement, scores, reasons, provider);
        verifyNoMoreInteractions(savedAnalysisService);
        verifyNoInteractions(llmService, analysisRelationGenerator);
    }

    @Test
    void buildAnalysisExport_withNullReasons() {
        String requirement = "Handle offline mode";
        Map<String, Integer> scores = Map.of("BP-1001", 70);
        String provider = "openai";

        SavedAnalysis expected = new SavedAnalysis();
        when(savedAnalysisService.buildExport(requirement, scores, null, provider)).thenReturn(expected);

        SavedAnalysis result = analysisFacade.buildAnalysisExport(requirement, scores, null, provider);

        assertThat(result).isSameAs(expected);
        verify(savedAnalysisService).buildExport(requirement, scores, null, provider);
    }

    @Test
    void buildAnalysisExport_withEmptyScores() {
        String requirement = "New requirement";
        Map<String, Integer> scores = Collections.emptyMap();
        Map<String, String> reasons = Collections.emptyMap();
        String provider = "gemini";

        SavedAnalysis expected = new SavedAnalysis();
        when(savedAnalysisService.buildExport(requirement, scores, reasons, provider)).thenReturn(expected);

        SavedAnalysis result = analysisFacade.buildAnalysisExport(requirement, scores, reasons, provider);

        assertThat(result).isSameAs(expected);
        verify(savedAnalysisService).buildExport(requirement, scores, reasons, provider);
    }

    @Test
    void generateRelationsFromScores_delegatesToGenerator() {
        Map<String, Integer> scores = Map.of("CP-1023", 85, "CR-1047", 60);
        List<RelationHypothesisDto> expected = List.of(
                new RelationHypothesisDto("CP-1023", "Source Node", "CR-1047", "Target Node",
                        "DEPENDS_ON", 0.9, "High co-occurrence")
        );
        when(analysisRelationGenerator.generate(scores)).thenReturn(expected);

        List<RelationHypothesisDto> result = analysisFacade.generateRelationsFromScores(scores);

        assertThat(result).isSameAs(expected);
        verify(analysisRelationGenerator).generate(scores);
        verifyNoMoreInteractions(analysisRelationGenerator);
        verifyNoInteractions(llmService, savedAnalysisService);
    }

    @Test
    void generateRelationsFromScores_withEmptyMap_returnsEmptyList() {
        Map<String, Integer> scores = Collections.emptyMap();
        when(analysisRelationGenerator.generate(scores)).thenReturn(Collections.emptyList());

        List<RelationHypothesisDto> result = analysisFacade.generateRelationsFromScores(scores);

        assertThat(result).isEmpty();
        verify(analysisRelationGenerator).generate(scores);
    }
}
