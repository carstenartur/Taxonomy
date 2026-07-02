package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.UnknownAnalysisProviderException;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisApiControllerTest {

    @Mock
    private TaxonomyService taxonomyService;

    @Mock
    private LlmService llmService;

    @Mock
    private AnalyzeRequirementUseCase analyzeRequirementUseCase;

    @Mock
    private MessageSource messageSource;

    private AnalysisApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AnalysisApiController(
                taxonomyService,
                llmService,
                mock(ExecutorService.class),
                new ObjectMapper(),
                analyzeRequirementUseCase,
                messageSource);
        when(taxonomyService.isInitialized()).thenReturn(true);
    }

    @Test
    void analyzeRejectsBlankBusinessTextBeforeDelegation() {
        AnalysisRequest request = new AnalysisRequest();
        request.setBusinessText("  ");

        ResponseEntity<AnalysisResult> response = controller.analyze(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(analyzeRequirementUseCase);
    }

    @Test
    void analyzeDelegatesToUseCaseAndReturnsBody() {
        AnalysisRequest request = new AnalysisRequest();
        request.setBusinessText("Need secure voice comms");
        request.setIncludeArchitectureView(true);
        request.setMaxArchitectureNodes(9);
        request.setProvider("gemini");

        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setStatus("SUCCESS");
        when(analyzeRequirementUseCase.analyze(any())).thenReturn(new AnalyzeRequirementResult(analysisResult));

        ResponseEntity<AnalysisResult> response = controller.analyze(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(analysisResult);
        verify(analyzeRequirementUseCase).analyze(any());
    }

    @Test
    void analyzeMapsUnknownProviderToBackwardCompatibleBadRequestBody() {
        AnalysisRequest request = new AnalysisRequest();
        request.setBusinessText("Need secure voice comms");
        request.setProvider("nope");
        when(analyzeRequirementUseCase.analyze(any()))
                .thenThrow(new UnknownAnalysisProviderException("nope"));

        ResponseEntity<AnalysisResult> response = controller.analyze(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<?, ?> body = (Map<?, ?>) (Object) response.getBody();
        assertThat(body.get("error")).isEqualTo("Unknown provider: nope");
        assertThat(body.get("validProviders")).isEqualTo(java.util.Arrays.toString(
                com.taxonomy.analysis.service.LlmProvider.values()));
    }
}
