package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenUseCase;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.JustifyLeafUseCase;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisApiWorkspaceBoundaryTest {

    @Mock private TaxonomyService taxonomyService;
    @Mock private ExecutorService analysisExecutor;
    @Mock private AnalyzeRequirementUseCase analyzeRequirementUseCase;
    @Mock private StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase;
    @Mock private AnalyzeNodeChildrenUseCase analyzeNodeChildrenUseCase;
    @Mock private JustifyLeafUseCase justifyLeafUseCase;
    @Mock private RepositoryStateService repositoryStateService;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private MessageSource messageSource;

    private AnalysisApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AnalysisApiController(
                taxonomyService,
                analysisExecutor,
                new ObjectMapper(),
                analyzeRequirementUseCase,
                streamRequirementAnalysisUseCase,
                analyzeNodeChildrenUseCase,
                justifyLeafUseCase,
                new AnalysisSseEventMapper(),
                repositoryStateService,
                workspaceResolver,
                messageSource);
        when(taxonomyService.isInitialized()).thenReturn(true);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(workspaceResolver.resolveCurrentContext())
                .thenThrow(new AccessDeniedException("foreign workspace"));
    }

    @Test
    void regularAnalysisNeverFallsBackFromDeniedPinToSharedContext() {
        AnalysisRequest request = new AnalysisRequest();
        request.setBusinessText("Need resilient communications");

        assertThatThrownBy(() -> controller.analyze(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("foreign workspace");

        verifyNoInteractions(analyzeRequirementUseCase);
    }

    @Test
    void streamingAnalysisValidatesPinBeforeStartingBackgroundWork() {
        assertThatThrownBy(() -> controller.analyzeStream(
                "Need resilient communications", null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("foreign workspace");

        verify(analysisExecutor, never()).execute(any());
        verifyNoInteractions(streamRequirementAnalysisUseCase);
    }
}
