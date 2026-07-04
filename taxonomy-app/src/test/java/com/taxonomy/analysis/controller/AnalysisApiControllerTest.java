package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisCommand;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase;
import com.taxonomy.analysis.usecase.UnknownAnalysisProviderException;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalysisApiControllerTest {

    @Mock
    private TaxonomyService taxonomyService;

    @Mock
    private LlmService llmService;

    @Mock
    private AnalyzeRequirementUseCase analyzeRequirementUseCase;

    @Mock
    private StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase;

    @Mock
    private MessageSource messageSource;

    @Mock
    private RepositoryStateService repositoryStateService;

    @Mock
    private WorkspaceResolver workspaceResolver;

    private ExecutorService analysisExecutor;
    private AnalysisApiController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        analysisExecutor = new AbstractExecutorService() {
            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
        controller = new AnalysisApiController(
                taxonomyService,
                llmService,
                analysisExecutor,
                new ObjectMapper(),
                analyzeRequirementUseCase,
                streamRequirementAnalysisUseCase,
                new AnalysisSseEventMapper(),
                repositoryStateService,
                workspaceResolver,
                messageSource);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(taxonomyService.isInitialized()).thenReturn(true);
        lenient().when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        lenient().when(workspaceResolver.resolveCurrentContext())
                .thenReturn(new WorkspaceContext("alice", "alice-ws", "draft"));
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

        ArgumentCaptor<com.taxonomy.analysis.usecase.AnalyzeRequirementCommand> commandCaptor =
                ArgumentCaptor.forClass(com.taxonomy.analysis.usecase.AnalyzeRequirementCommand.class);
        verify(analyzeRequirementUseCase).analyze(commandCaptor.capture());
        assertThat(commandCaptor.getValue().username()).isEqualTo("alice");
        assertThat(commandCaptor.getValue().workspaceContext())
                .isEqualTo(new WorkspaceContext("alice", "alice-ws", "draft"));
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

    @Test
    void analyzeStreamRejectsBlankBusinessTextBeforeDelegation() throws Exception {
        mockMvc.perform(get("/api/analyze-stream")
                        .param("businessText", " ")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "{\"status\":\"ERROR\",\"errorMessage\":\"businessText must not be blank\"}")));
        verifyNoInteractions(streamRequirementAnalysisUseCase);
    }

    @Test
    void analyzeStreamDelegatesToUseCaseAndMapsEventsToExistingSseContract() throws Exception {
        LlmCallDetail detail = new LlmCallDetail();
        detail.setPrompt("prompt");
        detail.setRawResponse("raw");
        detail.setProvider("GEMINI");
        detail.setDurationMs(42L);
        detail.setError("minor");

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AnalysisStreamEvent> handler = invocation.getArgument(1);
            handler.accept(new AnalysisStreamEvent.Phase("Working", 12));
            handler.accept(new AnalysisStreamEvent.Scores(
                    Map.of("CP", 80),
                    Map.of("CP", "reason"),
                    "Capabilities scored 80/100",
                    detail));
            handler.accept(new AnalysisStreamEvent.Expanding("CP", List.of("CP-1023")));
            handler.accept(new AnalysisStreamEvent.Complete(
                    "SUCCESS",
                    Map.of("CP", 80, "CR", 0),
                    List.of("warn"),
                    List.of()));
            return null;
        }).when(streamRequirementAnalysisUseCase).stream(any(), any());

        Locale previousLocale = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.GERMAN);
        SseEmitter emitter;
        try {
            emitter = controller.analyzeStream("Need secure voice comms", "gemini");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(previousLocale);
        }

        assertThat(emitter.getTimeout()).isEqualTo(120_000L);

        ArgumentCaptor<StreamRequirementAnalysisCommand> commandCaptor =
                ArgumentCaptor.forClass(StreamRequirementAnalysisCommand.class);
        verify(streamRequirementAnalysisUseCase).stream(commandCaptor.capture(), any());
        assertThat(commandCaptor.getValue().businessText()).isEqualTo("Need secure voice comms");
        assertThat(commandCaptor.getValue().provider()).isEqualTo("gemini");
        assertThat(commandCaptor.getValue().requestLocale()).isEqualTo(Locale.GERMAN);
    }

    @Test
    void analyzeStreamMapsUnknownProviderToBackwardCompatibleErrorEvent() throws Exception {
        doAnswer(invocation -> {
            throw new UnknownAnalysisProviderException("nope");
        }).when(streamRequirementAnalysisUseCase).stream(any(), any());

        mockMvc.perform(get("/api/analyze-stream")
                        .param("businessText", "Need secure voice comms")
                        .param("provider", "nope")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"ERROR\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"errorMessage\":\"Unknown provider: nope\"")));
    }
}
