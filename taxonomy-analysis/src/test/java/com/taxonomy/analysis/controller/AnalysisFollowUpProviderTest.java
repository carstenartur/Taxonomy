package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.*;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Executors;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class AnalysisFollowUpProviderTest {
    private MockMvc mvc;
    private AnalysisApiController controller;
    private LlmProviderConfig providers;
    private TaxonomyService taxonomy;
    private WorkspaceResolver resolver;
    private AnalyzeRequirementUseCase analyze;
    private AnalysisFollowUpProviders followUps;
    private LlmService llm;
    private final org.springframework.web.client.RestTemplate transport = mock(org.springframework.web.client.RestTemplate.class);
    private final com.taxonomy.analysis.demo.DemoScenarioCatalog scenarios = new com.taxonomy.analysis.demo.DemoScenarioCatalog();
    private final WorkspaceContext workspace = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
    @BeforeEach void setUp() {
        taxonomy = mock(TaxonomyService.class);
        var embedding = mock(LocalEmbeddingService.class);
        providers = new LlmProviderConfig(embedding, new com.taxonomy.analysis.demo.DemoReplayProviders(scenarios));
        ReflectionTestUtils.setField(providers, "llmProviderConfig", "OPENAI");
        var prompts = new PromptTemplateService(); prompts.loadDefaults();
        var gateways = new LlmGatewayRegistry(providers, transport, new ObjectMapper(), null, null, null, null);
        llm = new LlmService(providers, gateways, new ObjectMapper(), taxonomy,
                prompts, embedding, mock(SavedAnalysisService.class));
        resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(workspace);
        when(taxonomy.isInitialized()).thenReturn(true);
        var child = new TaxonomyNode(); child.setCode("BP-C"); child.setParentCode("BP");
        when(taxonomy.getChildrenOf("BP")).thenReturn(List.of(child));
        analyze = mock(AnalyzeRequirementUseCase.class);
        controller = new AnalysisApiController(taxonomy, Executors.newSingleThreadExecutor(), new ObjectMapper(),
                analyze, mock(StreamRequirementAnalysisUseCase.class),
                new AnalyzeNodeChildrenUseCase(taxonomy, llm), new JustifyLeafUseCase(taxonomy, llm),
                new AnalysisSseEventMapper(), mock(RepositoryStateService.class), resolver, mock(MessageSource.class));
        followUps = new AnalysisFollowUpProviders(providers);
        ReflectionTestUtils.setField(controller, "followUpProviders", followUps);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void explicitMockSelectionKeepsOriginalAndFollowUpOnTheSameSyntheticProvider(boolean streaming) {
        var root = new TaxonomyNode(); root.setCode("BP"); root.setNameEn("Business Processes");
        when(taxonomy.getRootNodes()).thenReturn(new java.util.ArrayList<>(List.of(root)));
        when(taxonomy.getRootCodes()).thenReturn(java.util.Set.of("BP"));
        var scope = new com.taxonomy.dto.AnalysisScope(java.util.Set.of("BP"), com.taxonomy.dto.AnalysisMode.TAXONOMIES_ONLY);
        if (streaming) {
            var events = new java.util.ArrayList<AnalysisStreamEvent>();
            new StreamRequirementAnalysisUseCase(llm, mock(AiPromptBudgetPolicy.class)).stream(
                    new StreamRequirementAnalysisCommand("requirement", "MOCK", null, scope), events::add);
            org.assertj.core.api.Assertions.assertThat(events).filteredOn(e -> e instanceof AnalysisStreamEvent.Complete)
                    .singleElement().satisfies(event -> org.assertj.core.api.Assertions.assertThat(
                            ((AnalysisStreamEvent.Complete) event).status()).isEqualTo("SUCCESS"));
        } else {
            var useCase = new AnalyzeRequirementUseCase(llm, mock(AiPromptBudgetPolicy.class), null, null, null,
                    mock(com.taxonomy.workspace.service.WorkspaceViewContextReadPort.class), null);
            var result = useCase.analyze(new AnalyzeRequirementCommand("requirement", false, 50, "MOCK", "alice",
                    workspace, null, scope)).analysisResult();
            org.assertj.core.api.Assertions.assertThat(result.getStatus()).isEqualTo("SUCCESS");
            org.assertj.core.api.Assertions.assertThat(result.getProvider()).isEqualTo("Mock");
        }
        org.assertj.core.api.Assertions.assertThat(providers.isMockMode()).isFalse();
        verifyNoInteractions(transport);
    }

    @Test void completedOperationRetainsItsProviderAfterGlobalDefaultsChange() throws Exception {
        when(analyze.analyze(any(AnalyzeRequirementCommand.class)))
                .thenReturn(new AnalyzeRequirementResult(new com.taxonomy.dto.AnalysisResult(java.util.Map.of(), List.of())));
        var response = mvc.perform(post("/api/analyze").contentType("application/json")
                .content("{\"businessText\":\"requirement\",\"provider\":\"LOCAL_ONNX\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String id = response.getHeader("X-Analysis-Operation-Id");
        ReflectionTestUtils.setField(providers, "llmProviderConfig", "GEMINI");
        mvc.perform(post("/api/justify-leaf").contentType("application/json")
                .content("{\"nodeCode\":\"BP-C\",\"businessText\":\"requirement\",\"operationId\":\"" + id + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.justification", containsString("LOCAL_ONNX")));
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement")
                .param("operationId", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.error", containsString("LOCAL_ONNX")));
        org.assertj.core.api.Assertions.assertThat(providers.getActiveProviderId()).isEqualTo(LlmProvider.GEMINI.id());
        verifyNoInteractions(transport);
    }

    @Test void resultAndLaterInteractiveStepsRetainTheAdmittedReasonLength() throws Exception {
        var configured = new java.util.concurrent.atomic.AtomicReference<>("detailed");
        AnalysisRuntimeSettings settings = new AnalysisRuntimeSettings() {
            @Override public int getInt(String key, int fallback) { return fallback; }
            @Override public String getString(String key, String fallback) { return configured.get(); }
        };
        ReflectionTestUtils.setField(controller, "analysisRuntimeSettings", settings);
        when(analyze.analyze(any(AnalyzeRequirementCommand.class))).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertEquals(com.taxonomy.dto.AnalysisReasonLength.DETAILED,
                    call.getArgument(0, AnalyzeRequirementCommand.class).analysisReasonLength());
            return new AnalyzeRequirementResult(new com.taxonomy.dto.AnalysisResult(java.util.Map.of(), List.of()));
        });
        var response = mvc.perform(post("/api/analyze").contentType("application/json")
                .content("{\"businessText\":\"requirement\",\"provider\":\"MOCK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisReasonLength").value("detailed"))
                .andReturn().getResponse();
        configured.set("short");
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement")
                .param("operationId", response.getHeader("X-Analysis-Operation-Id")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisReasonLength").value("detailed"));
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement")
                .param("provider", "MOCK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisReasonLength").value("short"));
        org.junit.jupiter.api.Assertions.assertNull(AnalysisReasonContext.current());
        verifyNoInteractions(transport);
    }

    @Test void operationIdsCannotSwitchOwnerSourceRequirementOrProvider() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        followUps.remember(id, "LOCAL_ONNX", "requirement", "alice", workspace);
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "other")
                .param("operationId", id)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement")
                .param("operationId", id).param("provider", "OPENAI")).andExpect(status().isBadRequest());
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice", "work-a", "other", "repo-a"));
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement")
                .param("operationId", id)).andExpect(status().isNotFound());
        when(resolver.resolveCurrentUsername()).thenReturn("bob");
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("bob", "work-a", "draft", "repo-a"));
        mvc.perform(post("/api/justify-leaf").contentType("application/json")
                .content("{\"nodeCode\":\"BP-C\",\"businessText\":\"requirement\",\"operationId\":\"" + id + "\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(transport);
    }

    @Test void authorizedDurableDemoFollowUpUsesAuthoredReplyAfterAnalysisBindingHasClosed() throws Exception {
        var cluster = mock(com.taxonomy.analysis.cluster.ClusterAnalysisStore.class);
        ReflectionTestUtils.setField(followUps, "cluster", cluster);
        String id = java.util.UUID.randomUUID().toString();
        String provider = "DEMO.TIME-TRACKING-V1.R0", text = scenarios.defaultScenario().r0Text();
        var command = new AnalyzeRequirementCommand(text, false, 50, provider, "alice", workspace);
        var operation = AnalysisOperationContexts.create(id, command, null);
        when(cluster.findAuthorized(id, "alice", workspace)).thenReturn(java.util.Optional.of(operation));
        when(cluster.command(operation)).thenReturn(command);
        // The replay was run-bound; the independent request starts at global mock defaults.
        ReflectionTestUtils.setField(providers, "llmMock", true);
        try (var ignored = providers.withRequestProvider(provider)) { }
        mvc.perform(post("/api/justify-leaf").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("nodeCode", "UA-1605", "businessText", text, "operationId", id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.justification", containsString("Szenario-Antwort")));
        org.assertj.core.api.Assertions.assertThat(providers.getActiveProviderId()).isEqualTo(LlmProvider.OPENAI.id());
        mvc.perform(post("/api/justify-leaf").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("nodeCode", "UNKNOWN", "businessText", text, "operationId", id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.justification", containsString("DEMO_REPLAY")));
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", text)
                .param("provider", provider)).andExpect(status().isBadRequest());
        verifyNoInteractions(transport);
    }

    @Test void queuedStreamExecutesWithTheAdmittedProviderAfterDefaultsChange() throws Exception {
        var queued = new java.util.ArrayList<Runnable>();
        var executor = new java.util.concurrent.AbstractExecutorService() {
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
            public void execute(Runnable work) { queued.add(work); }
        };
        ReflectionTestUtils.setField(controller, "analysisExecutor", executor);
        ReflectionTestUtils.setField(controller, "streamRequirementAnalysisUseCase",
                new StreamRequirementAnalysisUseCase(llm, mock(AiPromptBudgetPolicy.class)));
        var root = new TaxonomyNode(); root.setCode("BP"); root.setNameEn("Business Processes");
        when(taxonomy.getRootNodes()).thenReturn(new java.util.ArrayList<>(List.of(root)));
        when(taxonomy.getRootCodes()).thenReturn(java.util.Set.of("BP"));
        var result = mvc.perform(get("/api/analyze-stream").param("businessText", "requirement")
                .param("taxonomyRoots", "BP").param("analysisMode", "TAXONOMIES_ONLY"))
                .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(queued).hasSize(1);
        ReflectionTestUtils.setField(providers, "llmProviderConfig", "GEMINI");
        queued.getFirst().run();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("No API key configured for provider OPENAI")
                .doesNotContain("No API key configured for provider GEMINI");
        org.assertj.core.api.Assertions.assertThat(providers.getActiveProviderId()).isEqualTo(LlmProvider.GEMINI.id());
        verifyNoInteractions(transport);
    }

    @Test void initialInteractiveDefaultReturnsAnAuthorizedProviderPin() throws Exception {
        var response = mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisOperationId").isNotEmpty())
                .andExpect(jsonPath("$.analysisProviderId").value("OPENAI")).andReturn().getResponse();
        var id = new ObjectMapper().readTree(response.getContentAsString()).path("analysisOperationId").asString();
        ReflectionTestUtils.setField(providers, "llmProviderConfig", "GEMINI");
        mvc.perform(post("/api/justify-leaf").contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("nodeCode", "BP-C", "businessText", "requirement", "operationId", id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.justification", containsString("OPENAI")));
        verifyNoInteractions(transport);
    }

    @Test void explicitInteractiveProviderAlsoOwnsTheLeafExplanation() throws Exception {
        mvc.perform(post("/api/justify-leaf").contentType("application/json")
                .content("{\"nodeCode\":\"BP-C\",\"businessText\":\"requirement\",\"provider\":\"LOCAL_ONNX\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.justification", containsString("LOCAL_ONNX")));
    }
    @Test void interactiveExpansionUsesExplicitProviderRatherThanDefault() throws Exception {
        mvc.perform(get("/api/analyze-node").param("parentCode", "BP").param("businessText", "requirement")
                .param("provider", "LOCAL_ONNX"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.error", containsString("LOCAL_ONNX")));
    }
}
