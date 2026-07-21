package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenUseCase;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.JustifyLeafResult;
import com.taxonomy.analysis.usecase.JustifyLeafUseCase;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalysisApiControllerTest {

    @Mock private TaxonomyService taxonomyService;
    @Mock private AnalyzeRequirementUseCase analyzeRequirementUseCase;
    @Mock private StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase;
    @Mock private AnalyzeNodeChildrenUseCase analyzeNodeChildrenUseCase;
    @Mock private JustifyLeafUseCase justifyLeafUseCase;
    @Mock private MessageSource messageSource;
    @Mock private RepositoryStateService repositoryStateService;
    @Mock private WorkspaceResolver workspaceResolver;

    private ExecutorService analysisExecutor;
    private AnalysisApiController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        analysisExecutor = new AbstractExecutorService() {
            @Override public void shutdown() { }
            @Override public List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
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
        when(analyzeRequirementUseCase.analyze(any()))
                .thenReturn(new AnalyzeRequirementResult(analysisResult));

        ResponseEntity<AnalysisResult> response = controller.analyze(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(analysisResult);

        ArgumentCaptor<com.taxonomy.analysis.usecase.AnalyzeRequirementCommand> captor =
                ArgumentCaptor.forClass(com.taxonomy.analysis.usecase.AnalyzeRequirementCommand.class);
        verify(analyzeRequirementUseCase).analyze(captor.capture());
        assertThat(captor.getValue().username()).isEqualTo("alice");
        assertThat(captor.getValue().workspaceContext())
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
    }

    @Test
    void analyzeNodeDelegatesToTypedUseCase() throws Exception {
        when(analyzeNodeChildrenUseCase.analyze(any())).thenReturn(
                new AnalyzeNodeChildrenResult(
                        Map.of("CP-1023", 82),
                        Map.of("CP-1023", "reason"),
                        "prompt", "raw", "GEMINI", 42L, null));

        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "CP")
                        .param("businessText", "secure communications")
                        .param("parentScore", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores.CP-1023").value(82))
                .andExpect(jsonPath("$.provider").value("GEMINI"));

        ArgumentCaptor<com.taxonomy.analysis.usecase.AnalyzeNodeChildrenCommand> captor =
                ArgumentCaptor.forClass(com.taxonomy.analysis.usecase.AnalyzeNodeChildrenCommand.class);
        verify(analyzeNodeChildrenUseCase).analyze(captor.capture());
        assertThat(captor.getValue().parentScore()).isEqualTo(90);
    }

    @Test
    void analyzeNodeRejectsInvalidScoreBeforeUseCase() throws Exception {
        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "CP")
                        .param("businessText", "secure communications")
                        .param("parentScore", "101"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(analyzeNodeChildrenUseCase);
    }

    @Test
    void justifyLeafReturnsTypedResult() throws Exception {
        when(justifyLeafUseCase.justify(any()))
                .thenReturn(new JustifyLeafResult("CP-1023", "Traceable explanation"));

        mockMvc.perform(post("/api/justify-leaf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeCode":"CP-1023","businessText":"secure communications",
                                 "scores":{"CP-1023":82},"reasons":{"CP-1023":"reason"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCode").value("CP-1023"))
                .andExpect(jsonPath("$.justification").value("Traceable explanation"));
    }

    @Test
    void justifyLeafMapsInvalidRequestAndInternalFailureToProblemDetails() throws Exception {
        mockMvc.perform(post("/api/justify-leaf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"\",\"businessText\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid leaf justification request"));

        when(justifyLeafUseCase.justify(any()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        mockMvc.perform(post("/api/justify-leaf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeCode\":\"CP-1023\",\"businessText\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Leaf justification failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("provider unavailable"))));
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
                        "businessText must not be blank")));
        verifyNoInteractions(streamRequirementAnalysisUseCase);
    }

    @Test
    void analyzeStreamDelegatesAndMapsEvents() {
        LlmCallDetail detail = new LlmCallDetail();
        detail.setPrompt("prompt");
        detail.setRawResponse("raw");
        detail.setProvider("GEMINI");
        detail.setDurationMs(42L);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<AnalysisStreamEvent> handler = invocation.getArgument(1);
            handler.accept(new AnalysisStreamEvent.Phase("Working", 12));
            handler.accept(new AnalysisStreamEvent.Scores(
                    Map.of("CP", 80), Map.of("CP", "reason"),
                    "Capabilities scored 80/100", detail));
            handler.accept(new AnalysisStreamEvent.Complete(
                    "SUCCESS", Map.of("CP", 80), List.of(), List.of()));
            return null;
        }).when(streamRequirementAnalysisUseCase).stream(any(), any());

        Locale previous = org.springframework.context.i18n.LocaleContextHolder.getLocale();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.GERMAN);
        SseEmitter emitter;
        try {
            emitter = controller.analyzeStream("Need secure voice comms", "gemini");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(previous);
        }
        assertThat(emitter.getTimeout()).isEqualTo(120_000L);

        ArgumentCaptor<StreamRequirementAnalysisCommand> captor =
                ArgumentCaptor.forClass(StreamRequirementAnalysisCommand.class);
        verify(streamRequirementAnalysisUseCase).stream(captor.capture(), any());
        assertThat(captor.getValue().requestLocale()).isEqualTo(Locale.GERMAN);
    }

    @Test
    void analyzeStreamMapsUnknownProviderToErrorEvent() throws Exception {
        doAnswer(invocation -> {
            throw new UnknownAnalysisProviderException("nope");
        }).when(streamRequirementAnalysisUseCase).stream(any(), any());

        mockMvc.perform(get("/api/analyze-stream")
                        .param("businessText", "Need secure voice comms")
                        .param("provider", "nope")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Unknown provider: nope")));
    }
}
