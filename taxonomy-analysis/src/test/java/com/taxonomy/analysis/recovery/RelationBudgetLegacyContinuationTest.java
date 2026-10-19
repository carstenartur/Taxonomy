package com.taxonomy.analysis.recovery;

import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.AnalysisReasonContext;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisReasonLength;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisRecoveryView;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.extension.api.plugin.PluginIdentity;
import com.taxonomy.extension.api.plugin.PluginInvocation;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Legacy admission keeps the exact durable input identity and the normal checkpoint replay boundary. */
class RelationBudgetLegacyContinuationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCORING = "a".repeat(64);
    private static final String PROMPT = "Frozen source question";
    private final WorkspaceContext scope = new WorkspaceContext("alice", "workspace", "draft", "repo");
    private final TaxonomyService catalogue = mock(TaxonomyService.class);
    private final LlmService llm = mock(LlmService.class);
    private final LlmProviderConfig providers = mock(LlmProviderConfig.class);
    private final WorkspaceManager workspaces = mock(WorkspaceManager.class);
    private final RequirementRelationSearchService relations = new RequirementRelationSearchService(null, null, null, null);
    private RetainedStore store;
    private AnalysisContinuationService service;
    private AnalysisRequest saved;

    @BeforeEach
    void setUp() {
        var workspace = new UserWorkspace(); workspace.setUsername("alice");
        when(workspaces.getWorkspaceById("workspace")).thenReturn(workspace);
        var tree = List.of(node("Original catalogue"));
        when(catalogue.getFullTree()).thenReturn(tree);
        when(llm.recoveryPolicyFingerprint("MOCK")).thenReturn(SCORING);
        when(llm.recoveryPolicyFingerprint("GEMINI")).thenReturn(SCORING);
        when(llm.resolveAnalysisReasonLength(null)).thenReturn(AnalysisReasonLength.DETAILED);
        saved = new AnalysisRequest("Original requirement");
        saved.setProvider("MOCK"); saved.setResumable(true); saved.setContinuationId(UUID.randomUUID().toString());
        saved.setIncludeArchitectureView(true); saved.setMaxArchitectureNodes(23);
        saved.setAnalysisReasonLength(AnalysisReasonLength.STANDARD);
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("provider", "MOCK"); authority.put("binding", null); authority.put("scoringPolicy", SCORING);
        authority.put("relationPolicy", AnalysisCheckpointSession.digest(legacyFingerprint(24, 8, 512, 32)));
        authority.put("catalogueHash", AnalysisCheckpointSession.digest(JSON.writeValueAsString(tree)));
        saved.setContinuationAuthority(JSON.writeValueAsString(authority));
        String scoring = AnalysisCheckpointSession.digest("resumable-scoring-v1", saved.getBusinessText(), "true", "23",
                SCORING, JSON.writeValueAsString(tree), legacyFingerprint(24, 8, 512, 32));
        String inputHash = AnalysisCheckpointSession.digest(scoring, AnalysisReasonContext.POLICY, "standard");
        store = new RetainedStore(saved, inputHash);
        service = new AnalysisContinuationService(store, catalogue, llm, workspaces, JSON);
        ReflectionTestUtils.setField(service, "relations", relations);
        ReflectionTestUtils.setField(service, "providers", providers);
        ReflectionTestUtils.setField(relations, "maxCalls", 512);
        ReflectionTestUtils.setField(relations, "maxTotalCalls", 8192);
        ReflectionTestUtils.setField(relations, "maxSources", 256);
        ReflectionTestUtils.setField(relations, "maxWorkItems", 100_000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainedV8AuthorityResumesAfterBudgetIncreaseWithoutReplacingAcceptedEvidence(boolean distributed) {
        var request = continuation();
        var newCalls = new AtomicInteger();
        try (var execution = assertDoesNotThrow(() -> begin(request, distributed),
                "Increasing an execution budget must retain the admitted v8 input identity")) {
            if (distributed) {
                assertThat(execution.binding().inputHash()).isEqualTo(store.inputHash);
                assertThat(execution.binding().relationPolicyHash())
                        .isEqualTo(AnalysisCheckpointSession.digest(relations.recoveryPolicyFingerprint()));
                assertThat(execution.binding().sourceCohortSize()).isEqualTo(32);
            } else {
                assertThat(AnalysisCheckpointSession.sourceCohortSize()).isEqualTo(32);
                var replayed = AnalysisCheckpointSession.evaluateEvidence("SOURCES", "MOCK", List.of("CP"), PROMPT, () -> {
                    newCalls.incrementAndGet(); return new LlmCallDetail();
                });
                assertThat(replayed.getRawResponse()).isEqualTo("Retained validated answer");
                assertThat(AnalysisCheckpointSession.relationCallsSpent()).isEqualTo(24);
                AnalysisCheckpointSession.beforeRelationCall(8192);
                assertThat(store.spent).isEqualTo(25);
            }
        }
        assertThat(newCalls.get()).isZero();
        assertThat(store.admitted.getContinuationAuthority()).isEqualTo(saved.getContinuationAuthority());
        assertThat(store.admitted.getAnalysisReasonLength()).isEqualTo(AnalysisReasonLength.STANDARD);
        assertThat(relations.executionBudget().maxCalls()).isEqualTo(512);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionFreezesTheBudgetAndStartingCounterBeforePreferencesOrLedgerAdvance(boolean distributed) {
        try (var execution = begin(continuation(), distributed)) {
            var budget = distributed ? execution.binding().relationBudget() : AnalysisCheckpointSession.relationBudget();
            assertThat(budget.callsPerSlice()).isEqualTo(512);
            assertThat(budget.maxTotalCalls()).isEqualTo(8192);
            assertThat(budget.callsBeforeExecution()).isEqualTo(24);
            assertThat(budget.automatic()).isTrue();
            assertThat(budget.sourcesPerSlice()).isEqualTo(256);
            assertThat(budget.workItemsPerSlice()).isEqualTo(100_000);
            assertThat(budget.sourceCohortSize()).isEqualTo(32);
            assertThat(store.ledgerReads).isEqualTo(1);

            ReflectionTestUtils.setField(relations, "maxCalls", 1);
            ReflectionTestUtils.setField(relations, "maxTotalCalls", 30);
            ReflectionTestUtils.setField(relations, "autoContinue", false);
            ReflectionTestUtils.setField(relations, "maxSources", 64);
            ReflectionTestUtils.setField(relations, "maxWorkItems", 500);
            store.spent = 25;

            var stillFrozen = distributed ? execution.binding().relationBudget() : AnalysisCheckpointSession.relationBudget();
            assertThat(stillFrozen).isSameAs(budget);
            assertThat(stillFrozen.callsBeforeExecution()).isEqualTo(24);
            assertThat(relations.executionBudget().maxCalls()).isEqualTo(1);
            assertThat(store.ledgerReads).isEqualTo(1);
            if (distributed) {
                var restored = JSON.readValue(JSON.writeValueAsString(execution.binding()), AnalysisContinuationBinding.class);
                assertThat(restored).isEqualTo(execution.binding());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedReplayDoesNotRecaptureOrChargeTheRelationLedger(boolean distributed) {
        store.completed = new AnalysisResult(Map.of(), List.of(node("Original catalogue")));
        try (var execution = begin(continuation(), distributed)) {
            assertThat(execution.replay()).isSameAs(store.completed);
            assertThat(execution.binding()).isNull();
            assertThat(AnalysisCheckpointSession.active()).isFalse();
        }
        assertThat(store.ledgerReads).isZero();
        assertThat(store.spent).isEqualTo(24);
    }

    @Test
    void historicalDistributedBindingsWithoutBudgetFieldsStillDeserialize() {
        String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        String wire = JSON.writeValueAsString(Map.of("id", id, "token", token, "inputHash", SCORING,
                "scoringPolicyHash", SCORING, "relationPolicyHash", SCORING));
        var binding = JSON.readValue(wire, AnalysisContinuationBinding.class);
        assertThat(binding).isEqualTo(new AnalysisContinuationBinding(id, token, SCORING, SCORING, SCORING));
        assertThat(binding.sourceCohortSize()).isZero();
        assertThat(binding.relationBudget()).isNull();
    }

    @Test
    void interruptedV9RunRetainsItsAdmittedSourceCohortBeforeAnyReportExists() {
        String currentPolicy = relations.recoveryPolicyFingerprint();
        var authority = (tools.jackson.databind.node.ObjectNode) JSON.readTree(saved.getContinuationAuthority());
        authority.put("relationPolicy", AnalysisCheckpointSession.digest(currentPolicy));
        saved.setContinuationAuthority(JSON.writeValueAsString(authority));
        String original = AnalysisCheckpointSession.digest("resumable-scoring-v1", saved.getBusinessText(), "true", "23",
                SCORING, JSON.writeValueAsString(catalogue.getFullTree()), currentPolicy);
        store = new RetainedStore(saved, AnalysisCheckpointSession.digest(original, AnalysisReasonContext.POLICY, "standard"));
        store.accepted = null;
        service = new AnalysisContinuationService(store, catalogue, llm, workspaces, JSON);
        ReflectionTestUtils.setField(service, "relations", relations);
        ReflectionTestUtils.setField(service, "providers", providers);
        ReflectionTestUtils.setField(relations, "maxSources", 16);
        var calls = new AtomicInteger();
        java.util.function.Supplier<LlmCallDetail> complete = () -> {
            calls.incrementAndGet();
            var detail = new LlmCallDetail(); detail.setProvider("MOCK"); detail.setPrompt(PROMPT);
            detail.setRawResponse("Retained validated answer"); return detail;
        };

        try (var interrupted = begin(continuation(), false)) {
            assertThat(AnalysisCheckpointSession.previousRelations()).isNull();
            AnalysisCheckpointSession.evaluateEvidence("SOURCES", "MOCK", List.of("CP"), PROMPT, complete);
            // Close without publishing a report: accepted source checkpoints remain durable.
        }
        ReflectionTestUtils.setField(relations, "maxSources", 64);

        try (var resumed = begin(continuation(), false)) {
            assertThat(AnalysisCheckpointSession.previousRelations()).isNull();
            assertThat(AnalysisCheckpointSession.sourceCohortSize()).isEqualTo(16);
            assertThat(AnalysisCheckpointSession.relationBudget().sourcesPerSlice()).isEqualTo(64);
            var replayed = AnalysisCheckpointSession.evaluateEvidence("SOURCES", "MOCK", List.of("CP"), PROMPT, complete);
            assertThat(replayed.getRawResponse()).isEqualTo("Retained validated answer");
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.persistedSourceCohort).isEqualTo(16);
    }

    @Test
    void relationsExtensionVerifiesTheExactOldSourceSignatureAndRetainsItsAuthority() {
        var view = new ViewContext("commit", "draft", null, false, false, false);
        var views = mock(WorkspaceViewContextReadPort.class);
        when(views.getViewContext("alice", "draft", scope)).thenReturn(view);
        ReflectionTestUtils.setField(service, "views", views);
        var evidence = new AnalysisResult(Map.of("CP", 72d), catalogue.getFullTree());
        evidence.setProvider("MOCK"); evidence.setAnalysisProviderId("MOCK");
        evidence.setStatus("SUCCESS"); evidence.setViewContext(view);
        evidence.setAnalysisScope(saved.getAnalysisScope());
        evidence.setAnalysisCoverage(AnalysisCoverage.derive(evidence.getTree(), evidence.getRawScores(), evidence.getScores(), Map.of(), null));
        store.source = new AnalysisContinuationStore.Snapshot(new AnalysisRecoveryView(saved.getContinuationId(), 3,
                "COMPLETED", 1, 1, null, List.of()), evidence, saved);
        var decision = new AnalysisRequest(); decision.setResumable(true);
        decision.setContinuationId(UUID.randomUUID().toString()); decision.setProvider("GEMINI");
        decision.setRelationSourceContinuationId(saved.getContinuationId()); decision.setRelationSourceVersion(3L);

        var resolved = service.resolveRelationsRequest(decision, "alice", scope);

        assertThat(store.verifiedSourceHash).isEqualTo(store.inputHash);
        assertThat(resolved.getContinuationAuthority()).isEqualTo(saved.getContinuationAuthority());
        assertThat(resolved.getProvider()).isEqualTo("MOCK");
        assertThat(resolved.getRelationSourceContinuationId()).isEqualTo(saved.getContinuationId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingDepthStillRejectsTheSavedAuthorityBeforeAClaim(boolean distributed) {
        ReflectionTestUtils.setField(relations, "maxDepth", 9);
        assertThatThrownBy(() -> { try (var ignored = begin(continuation(), distributed)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(store.admitted).isNull();
    }

    @Test
    void changingProviderStillRejectsEvenWhenItsScoringPolicyStringMatches() {
        var request = continuation(); request.setProvider("GEMINI");
        assertThatThrownBy(() -> { try (var ignored = begin(request, false)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(store.admitted).isNull();
    }

    @Test
    void changingCatalogueStillRejectsTheSavedAuthorityBeforeAClaim() {
        when(catalogue.getFullTree()).thenReturn(List.of(node("Changed catalogue")));
        assertThatThrownBy(() -> { try (var ignored = begin(continuation(), false)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(store.admitted).isNull();
    }

    @Test
    void changingProviderBindingStillRejectsTheSavedAuthorityBeforeAClaim() {
        when(providers.captureProviderBinding("MOCK")).thenReturn(new PluginInvocation(
                new PluginIdentity("local-provider", "1.0.0", "b".repeat(64)), "c".repeat(64)));
        assertThatThrownBy(() -> { try (var ignored = begin(continuation(), false)) { } })
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        assertThat(store.admitted).isNull();
    }

    private AnalysisContinuationService.Execution begin(AnalysisRequest request, boolean distributed) {
        return distributed ? service.beginDistributed(request, "alice", scope, 23)
                : service.begin(request, "alice", scope, 23);
    }

    private AnalysisRequest continuation() {
        var request = JSON.readValue(JSON.writeValueAsString(saved), AnalysisRequest.class);
        request.setContinuationAction("CONTINUE"); request.setContinuationVersion(1L);
        request.setContinuationAuthority("client authority must be ignored");
        return request;
    }

    private static TaxonomyNodeDto node(String name) {
        var node = new TaxonomyNodeDto(); node.setCode("CP"); node.setTaxonomyRoot("CP"); node.setNameEn(name); return node;
    }

    private static String legacyFingerprint(int calls, int depth, int work, int sources) {
        return "relation-downwalk-v8/source-call-reservation=half/relation-semantics-v2/relation-source-context-v1/provider-profiles/taxonomy-cohorts/custom-defaults=3,16000/true/"
                + "Options[limits=Limits[maxCalls=" + calls + ", maxDepth=" + depth + ", batchSize=10, maxWorkItems=" + work
                + "], maxSources=" + sources + ", maxPromptCharacters=120000]/batch-override=null/prompt-override=null";
    }

    /** Persistence retains an exact input hash; accepting a changed signature would be a test failure. */
    private static final class RetainedStore extends AnalysisContinuationStore {
        private final AnalysisRequest original;
        private final String inputHash;
        private AnalysisRequest admitted;
        private int spent = 24;
        private int ledgerReads;
        private AnalysisResult completed;
        private Snapshot source;
        private String verifiedSourceHash;
        private int persistedSourceCohort;
        private final AnalysisCheckpointSession.Question retainedQuestion;
        private AnalysisCheckpointSession.Checkpoint accepted;

        RetainedStore(AnalysisRequest original, String inputHash) {
            super(null, JSON); this.original = original; this.inputHash = inputHash;
            var question = AnalysisCheckpointSession.evidenceQuestion("SOURCES", "MOCK", List.of("CP"), PROMPT);
            retainedQuestion = question;
            var detail = new LlmCallDetail(); detail.setProvider("MOCK"); detail.setPrompt(PROMPT);
            detail.setRawResponse("Retained validated answer"); detail.setScores(Map.of());
            accepted = new AnalysisCheckpointSession.Checkpoint(question, "SUCCESS", detail);
        }
        @Override public Optional<AnalysisRequest> originalRequest(String id, WorkspaceContext scope) { return Optional.of(original); }
        @Override public Claim begin(AnalysisRequest request, WorkspaceContext scope, String signature, List<TaxonomyNodeDto> tree) {
            if (!inputHash.equals(signature)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The original input identity changed");
            admitted = request;
            return new Claim(request.getContinuationId(), UUID.randomUUID().toString(), completed);
        }
        @Override public Snapshot relationInput(AnalysisRequest request, WorkspaceContext scope) { return source; }
        @Override public void verifySourceSignature(String id, WorkspaceContext scope, String signature) {
            if (!inputHash.equals(signature)) throw new ResponseStatusException(HttpStatus.CONFLICT, "The original source identity changed");
            verifiedSourceHash = signature;
        }
        @Override public AnalysisCheckpointSession.Checkpoint prepare(Claim claim, AnalysisCheckpointSession.Question question) {
            assertThat(question).isEqualTo(retainedQuestion);
            return accepted == null ? new AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null) : accepted;
        }
        @Override public void finish(Claim claim, AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) {
            accepted = new AnalysisCheckpointSession.Checkpoint(question, state, detail);
        }
        @Override public int freezeRelationSourceCohort(Claim claim, int proposed) {
            if (persistedSourceCohort == 0) persistedSourceCohort = proposed;
            return persistedSourceCohort;
        }
        @Override public int relationCallsSpent(Claim claim) { ledgerReads++; return spent; }
        @Override public void beforeRelationCall(Claim claim, int maximum) {
            assertThat(maximum).isEqualTo(8192); spent++;
        }
        @Override public String state(Claim claim) { return "RUNNING"; }
        @Override public String boundOperation(String id) { return null; }
        @Override public void interrupted(Claim claim) { }
    }
}
