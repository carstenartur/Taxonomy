package com.taxonomy.analysis.service;

import com.taxonomy.analysis.recovery.AnalysisCheckpointSession;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.EmbeddingModelIdentity;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.snapshot.*;
import org.springframework.test.util.ReflectionTestUtils;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.ProductCoverageGap;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Full scoring orchestration with real prompts/parser and only catalogue/transport boundaries replaced. */
class RootScoringFlowTest {
    private static TaxonomyNode node(String code, String parent, String root) {
        var n = new TaxonomyNode();
        n.setCode(code); n.setNameEn(code); n.setParentCode(parent); n.setTaxonomyRoot(root);
        n.setLevel(parent == null ? 0 : 1);
        return n;
    }

    private static final class Catalogue extends TaxonomyService {
        private final TaxonomyNode root = node("BP", null, "BP");
        private final TaxonomyNode child = node("BP-1", "BP", "BP");
        private final boolean withChild;
        Catalogue(boolean withChild) { super(null, null, null); this.withChild = withChild; }
        @Override public List<TaxonomyNode> getRootNodes() {
            return FrozenCatalogueContext.current() == null ? new ArrayList<>(List.of(root)) : super.getRootNodes();
        }
        @Override public List<TaxonomyNode> getChildrenOf(String parent) {
            if (FrozenCatalogueContext.current() != null) return super.getChildrenOf(parent);
            return withChild && parent.equals("BP") ? List.of(child) : List.of();
        }
        @Override public TaxonomyNode getNodeByCode(String code) {
            if (FrozenCatalogueContext.current() != null) return super.getNodeByCode(code);
            return code.equals("BP") ? root : withChild && code.equals("BP-1") ? child : null;
        }
        @Override public List<com.taxonomy.dto.TaxonomyNodeDto> getFullTree() { return List.of(); }
    }

    private static final class Provider extends LlmProviderConfig {
        private final LlmProvider provider;
        Provider(LlmProvider provider) { super(null); this.provider = provider; }
        @Override public com.taxonomy.extension.api.llm.ProviderId getActiveProviderId() { return provider.id(); }
        @Override public String getActiveProviderName() { return provider.name(); }
        @Override public String getApiKey(LlmProvider requested) { return "test-key"; }
    }

    private static class TwoRootCatalogue extends TaxonomyService {
        private final TaxonomyNode communication = node("CO", null, "CO");
        private final TaxonomyNode communicationChild = node("CO-1000", "CO", "CO");
        private final TaxonomyNode application = node("UA", null, "UA");
        TwoRootCatalogue() { super(null, null, null); }
        @Override public List<TaxonomyNode> getRootNodes() { return List.of(communication, application); }
        @Override public List<TaxonomyNode> getChildrenOf(String code) {
            return code.equals("CO") ? List.of(communicationChild) : List.of();
        }
        @Override public TaxonomyNode getNodeByCode(String code) {
            return switch (code) {
                case "CO" -> communication;
                case "CO-1000" -> communicationChild;
                case "UA" -> application;
                default -> null;
            };
        }
        @Override public List<TaxonomyNodeDto> getFullTree() {
            var co = dto(communication);
            co.setChildren(List.of(dto(communicationChild)));
            return List.of(co, dto(application));
        }
        private TaxonomyNodeDto dto(TaxonomyNode source) {
            var dto = new TaxonomyNodeDto();
            dto.setCode(source.getCode()); dto.setTaxonomyRoot(source.getTaxonomyRoot());
            dto.setParentCode(source.getParentCode()); dto.setLevel(source.getLevel());
            dto.setNameEn(source.getNameEn());
            return dto;
        }
    }

    private static final class Gateway implements LlmGateway {
        final List<String> prompts = new ArrayList<>();
        final List<String> replies;
        Runnable beforeResponse = () -> { };
        Gateway(String... replies) { this.replies = List.of(replies); }
        @Override public String sendHttpRequest(String prompt, String key) {
            assertEquals("test-key", key);
            prompts.add(prompt);
            beforeResponse.run();
            return replies.get(prompts.size() - 1);
        }
        @Override public String extractResponseText(String body) { return body; }
        @Override public String providerName() { return "fake remote"; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPreferenceChangeDuringRootWorkCannotChangeLaterReasonsInThatRun(boolean streaming) {
        var configured = new java.util.concurrent.atomic.AtomicReference<>("detailed");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        AnalysisRuntimeSettings settings = new AnalysisRuntimeSettings() {
            @Override public int getInt(String key, int fallback) { return fallback; }
            @Override public String getString(String key, String fallback) {
                assertEquals("analysis.reason-length", key);
                reads.incrementAndGet();
                return configured.get();
            }
        };
        var gateway = new Gateway("{\"BP\":{\"score\":50,\"reason\":\"Root evidence\"}}",
                "{\"BP-1\":{\"score\":50,\"reason\":\"Child evidence\"}}");
        gateway.beforeResponse = () -> configured.set("short");
        var llm = service(LlmProvider.OPENAI, true, gateway, null);
        ReflectionTestUtils.setField(llm, "analysisRuntimeSettings", settings);
        if (streaming) {
            var callback = new Capture();
            llm.analyzeStreaming("requirement", callback);
            assertEquals("SUCCESS", callback.status);
            assertEquals(Map.of("BP", 50d, "BP-1", 50d), callback.scores);
        } else {
            var result = llm.analyzeWithBudget("requirement");
            assertEquals("SUCCESS", result.getStatus());
            assertEquals(com.taxonomy.dto.AnalysisReasonLength.DETAILED, result.getAnalysisReasonLength());
            assertEquals(Map.of("BP", "Root evidence", "BP-1", "Child evidence"), result.getReasons());
        }
        assertEquals(2, gateway.prompts.size());
        assertTrue(gateway.prompts.stream().allMatch(prompt -> prompt.contains("reason-length-v1): detailed")));
        assertEquals(1, reads.get(), "A run captures the preference once before the first root task");
        assertNull(AnalysisReasonContext.current(), "A reused worker must not inherit the last run's mode");
        assertEquals(com.taxonomy.dto.AnalysisReasonLength.SHORT, llm.resolveAnalysisReasonLength(null));
    }

    private static LlmService service(LlmProvider provider, boolean withChild, Gateway gateway,
                                      LocalEmbeddingService embeddings) {
        return service(provider, new Catalogue(withChild), gateway, embeddings);
    }

    @Test
    void streamingCompletionRetainsParsedReasonsWithoutDependingOnEarlierEvents() {
        var gateway = new Gateway("{\"BP\":{\"score\":50,\"reason\":\"Root evidence\"}}",
                "{\"BP-1\":{\"score\":50,\"reason\":\"Child evidence\"}}");
        var llm = service(LlmProvider.OPENAI, true, gateway, null);
        var budget = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(
                    String text, String provider) { return null; }
        };
        var useCase = new com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase(llm, budget);
        var mapper = new com.taxonomy.analysis.controller.AnalysisSseEventMapper();
        var events = new ArrayList<com.taxonomy.analysis.controller.AnalysisSseEventMapper.MappedEvent>();

        useCase.stream(new com.taxonomy.analysis.usecase.StreamRequirementAnalysisCommand(
                "requirement", null, java.util.Locale.ENGLISH), event -> events.add(mapper.map(event)));

        var batches = events.stream().filter(event -> event.name().equals("scores"))
                .map(event -> (Map<?, ?>) event.payload()).toList();
        assertEquals(Map.of("BP", "Root evidence"), batches.getFirst().get("reasons"));
        assertEquals(Map.of("BP-1", "Child evidence"), batches.getLast().get("reasons"));
        var terminal = events.getLast();
        assertEquals("complete", terminal.name());
        var payload = (Map<?, ?>) terminal.payload();
        assertEquals(Map.of("BP", 50d, "BP-1", 50d), payload.get("totalScores"));
        assertEquals(Map.of("BP", "Root evidence", "BP-1", "Child evidence"), payload.get("reasons"),
                "The authoritative terminal event must restore explanations even if an incremental event was missed");
    }

    @Test
    void streamingCompletionPreservesReasonsAlongsideUnresolvedRootCoverage() {
        var gateway = new Gateway("""
                {"CO":{"status":"UNRESOLVED","score":null,
                "reason":"Connectivity is unspecified.","question":"Must devices exchange entries?"}}
                """, """
                {"UA":{"status":"RELEVANT","score":40,
                "reason":"A time-recording application is required.","question":""}}
                """);
        var llm = service(LlmProvider.OPENAI, new TwoRootCatalogue(), gateway, null);
        var budget = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(
                    String text, String provider) { return null; }
        };
        var useCase = new com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase(llm, budget);
        var mapper = new com.taxonomy.analysis.controller.AnalysisSseEventMapper();
        var events = new ArrayList<com.taxonomy.analysis.controller.AnalysisSseEventMapper.MappedEvent>();

        useCase.stream(new com.taxonomy.analysis.usecase.StreamRequirementAnalysisCommand(
                "Record time; connectivity is unspecified.", null, java.util.Locale.ENGLISH),
                event -> events.add(mapper.map(event)));

        var terminal = events.getLast();
        assertEquals("complete", terminal.name());
        var payload = (Map<?, ?>) terminal.payload();
        assertEquals("PARTIAL", payload.get("status"));
        assertEquals(Map.of("UA", 40d), payload.get("rawScores"));
        assertEquals(Map.of("CO", "Connectivity is unspecified.", "UA", "A time-recording application is required."),
                payload.get("reasons"));
        var coverage = assertInstanceOf(AnalysisCoverage.class, payload.get("analysisCoverage"));
        var unknown = coverage.nodes().get("CO");
        assertEquals(AnalysisCoverage.State.UNKNOWN, unknown.state());
        assertNull(unknown.score());
        assertNull(unknown.effectiveRelevance());
        assertTrue(unknown.reason().contains("Must devices exchange entries?"));
        assertEquals("BLOCKED_BY:CO", coverage.nodes().get("CO-1000").reason());
    }

    private static LlmService service(LlmProvider provider, TaxonomyService catalogue, Gateway gateway,
                                      LocalEmbeddingService embeddings) {
        var config = new Provider(provider);
        var registry = new LlmGatewayRegistry(config, new RestTemplate(), new ObjectMapper(), null, null, null) {
            @Override public LlmGateway getGatewayById(com.taxonomy.extension.api.llm.ProviderId selected) { return gateway; }
        };
        var prompts = new PromptTemplateService();
        prompts.loadDefaults();
        return new LlmService(config, registry, new ObjectMapper(), catalogue,
                prompts, embeddings, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstRootPromptUsesCatalogueContextWhileZeroStillStopsDescent(boolean frozenCatalogue) {
        var catalogue = new Catalogue(true);
        catalogue.root.setDescriptionEn("C3 Taxonomy – Business Processes");
        String sourceDescription = "Full original top-level catalogue description.";
        catalogue.child.setDescriptionEn(sourceDescription);
        try (var ignored = frozenCatalogue ? frozen(List.of(catalogue.root, catalogue.child), null) : null;
             var language = AnalysisLanguageContext.open(com.taxonomy.dto.AnalysisLanguage.ENGLISH)) {
            if (frozenCatalogue) catalogue.child.setDescriptionEn("Current catalogue changed after admission");
            var gateway = new Gateway("{\"BP\":0}", "{\"BP\":0}");
            var llm = service(LlmProvider.OPENAI, catalogue, gateway, null);
            var previewRoot = catalogue.getRootNodes().getFirst();
            if (frozenCatalogue) previewRoot.setDescriptionEn("Mutable caller description");
            String preview = ReflectionTestUtils.invokeMethod(llm, "assessmentPrompt",
                    "Arbeitszeiterfassung", List.of(previewRoot), 100d, ScoreAssessmentKind.ROOT_RELEVANCE);

            AnalysisResult result = llm.analyzeWithBudget("Arbeitszeiterfassung");
            assertEquals("SUCCESS", result.getStatus());
            assertEquals(Map.of("BP", 0d), result.getRawScores());
            var callback = new Capture();
            llm.analyzeStreaming("Arbeitszeiterfassung", callback);
            assertEquals("SUCCESS", callback.status);
            assertEquals(Map.of("BP", 0d), callback.scores);
            assertEquals(2, gateway.prompts.size(), "Each run assesses only its root when relevance is zero");
            assertEquals(preview, gateway.prompts.getFirst());
            assertEquals(preview, gateway.prompts.getLast());
            assertTrue(preview.contains(sourceDescription), "The first root assessment needs its catalogue meaning");
            assertFalse(preview.contains("Current catalogue changed after admission"));
            assertFalse(preview.contains("Mutable caller description"));
            assertTrue(preview.contains("not additional candidates"));
            String candidates = preview.substring(preview.indexOf("Nodes to evaluate (only the following candidates):"));
            assertTrue(candidates.contains("BP: BP - C3 Taxonomy – Business Processes"));
            assertFalse(candidates.contains("BP-1"), "Context must not change the root question's candidate IDs");
        }
    }

    @Test
    void rootCatalogueContextInvalidatesTheFormerRecoveryPolicy() {
        var llm = service(LlmProvider.OPENAI, false, new Gateway(), null);
        String providerPolicy = ReflectionTestUtils.invokeMethod(llm, "recoveryProviderPolicyFingerprint", "OPENAI");
        String formerPolicy = AnalysisCheckpointSession.digest(providerPolicy,
                PromptTemplateService.childAllocationContract(100), PromptTemplateService.functionalScopeContract(),
                AnalysisReasonContext.POLICY, com.taxonomy.dto.AnalysisReasonLength.STANDARD.value());
        assertNotEquals(formerPolicy, llm.recoveryPolicyFingerprint("OPENAI"),
                "A root answer obtained without catalogue context must not be reused under the new policy");
    }

    @Test
    void anAnswerFromTheNumericOnlyRootPolicyCannotBeReusedUnderTheClarificationPolicy() {
        var llm = service(LlmProvider.OPENAI, false, new Gateway(), null);
        String providerPolicy = ReflectionTestUtils.invokeMethod(llm, "recoveryProviderPolicyFingerprint", "OPENAI");
        String formerPolicy = AnalysisCheckpointSession.digest(providerPolicy,
                PromptTemplateService.childAllocationContract(100), PromptTemplateService.functionalScopeContract(),
                TaxonomyService.ROOT_ASSESSMENT_CONTEXT_POLICY,
                AnalysisReasonContext.POLICY, com.taxonomy.dto.AnalysisReasonLength.STANDARD.value());
        assertNotEquals(formerPolicy, llm.recoveryPolicyFingerprint("OPENAI"),
                "Previously forced numeric answers are evidence under a different assessment policy");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 20, 100})
    void remoteSingletonRootRetainsIndependentRelevance(double score) {
        var gateway = new Gateway("{\"BP\":{\"score\":" + score + ",\"reason\":\"root evidence\"}}");
        AnalysisResult result = service(LlmProvider.OPENAI, false, gateway, null).analyzeWithBudget("requirement");
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(score, result.getRawScores().get("BP"));
        assertEquals("root evidence", result.getReasons().get("BP"));
        assertEquals(1, gateway.prompts.size());
        assertTrue(gateway.prompts.getFirst().contains("independent relevance"));
        assertFalse(gateway.prompts.getFirst().contains("sum to 100"));
    }

    @Test
    void anOpenRootIsPartialWithItsMeaningPreservedInsteadOfAParseFailure() {
        var gateway = new Gateway("""
                {"BP":{"status":"UNRESOLVED","score":null,
                "reason":"The requested process boundary is still open.",
                "question":"Which process must the application support?"}}
                """);
        var llm = service(LlmProvider.OPENAI, true, gateway, null);
        AnalysisResult result = llm.analyzeWithBudget("The process is not specified yet.");
        assertEquals("PARTIAL", result.getStatus());
        assertFalse(result.getRawScores().containsKey("BP"));
        assertFalse(result.getRawScores().containsKey("BP-1"));
        assertEquals("The requested process boundary is still open.", result.getReasons().get("BP"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("Which process must the application support?")));
        assertFalse(result.getWarnings().stream().anyMatch(w -> w.contains("Failed to parse")));
        assertEquals(1, gateway.prompts.size(), "An open root must not spend an invented child budget");
        assertEquals(0L, llm.getDiagnostics().get("failedCalls"), "A valid question is not a failed provider call");
        assertEquals(true, llm.getDiagnostics().get("lastCallSuccess"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOpenCommunicationRootKeepsItsQuestionWhileTheApplicationIsStillAssessed(boolean streaming) {
        var gateway = new Gateway("""
                {"CO":{"status":"UNRESOLVED","score":null,
                "reason":"Online and isolated operation are both possible from the requirement.",
                "question":"Must entries be exchanged between devices?"}}
                """, """
                {"UA":{"status":"RELEVANT","score":40,
                "reason":"The user needs an application to record time entries.","question":""}}
                """);
        var llm = service(LlmProvider.OPENAI, new TwoRootCatalogue(), gateway, null);
        Map<String, Double> scores;
        AnalysisCoverage coverage;
        String status;
        if (streaming) {
            var callback = new Capture();
            llm.analyzeStreaming("Record working time; connectivity is unspecified.", callback);
            scores = callback.scores; coverage = callback.coverage; status = callback.status;
            assertEquals("Must entries be exchanged between devices?",
                    callback.details.getFirst().getRootAssessments().get("CO").question());
        } else {
            var result = llm.analyzeWithBudget("Record working time; connectivity is unspecified.");
            scores = result.getRawScores(); coverage = result.getAnalysisCoverage(); status = result.getStatus();
        }
        assertEquals("PARTIAL", status);
        assertEquals(Map.of("UA", 40d), scores);
        assertNotNull(coverage);
        var co = coverage.nodes().get("CO");
        assertEquals(AnalysisCoverage.State.UNKNOWN, co.state());
        assertNull(co.score()); assertNull(co.effectiveRelevance());
        assertTrue(co.reason().startsWith("CLARIFICATION:"));
        assertTrue(co.reason().contains("Must entries be exchanged between devices?"));
        assertEquals("BLOCKED_BY:CO", coverage.nodes().get("CO-1000").reason());
        assertEquals(AnalysisCoverage.State.RELEVANT, coverage.nodes().get("UA").state());
        assertTrue(coverage.hasOpenEvaluations());
        assertEquals(2, gateway.prompts.size(), "The next root continues, without a retry or a guessed child budget");
        assertTrue(gateway.prompts.getFirst().contains("existing, shared or externally operated service"));
        assertTrue(gateway.prompts.getFirst().contains("UNRESOLVED with score null"));
    }

    @Test
    void streamingUsesTheAdmittedCatalogueWithoutAnotherLookupAfterReceivingEvidence() {
        var admitted = new java.util.concurrent.atomic.AtomicBoolean();
        var catalogue = new TwoRootCatalogue() {
            @Override public List<TaxonomyNodeDto> getFullTree() {
                if (admitted.get()) throw new IllegalStateException("The catalogue is no longer available");
                return super.getFullTree();
            }
        };
        var gateway = new Gateway("""
                {"CO":{"status":"UNRESOLVED","score":null,"reason":"Connectivity is open.","question":"Online?"}}
                """, "{\"UA\":40}");
        gateway.beforeResponse = () -> admitted.set(true);
        var callback = new Capture();
        assertDoesNotThrow(() -> service(LlmProvider.OPENAI, catalogue, gateway, null)
                .analyzeStreaming("Record time.", callback));
        assertEquals("PARTIAL", callback.status);
        assertEquals(Map.of("UA", 40d), callback.scores);
        assertEquals(AnalysisCoverage.State.UNKNOWN, callback.coverage.nodes().get("CO").state());
        assertTrue(callback.coverage.nodes().get("CO").reason().contains("Online?"));
    }

    @Test
    void streamingRootRetainsIndependentTwenty() {
        var gateway = new Gateway("{\"BP\":20}");
        var callback = new Capture();
        service(LlmProvider.OPENAI, false, gateway, null).analyzeStreaming("requirement", callback);
        assertEquals("SUCCESS", callback.status);
        assertEquals(Map.of("BP", 20d), callback.scores);
    }

    @Test
    void embeddingRootRetainsIndependentTwentyWithoutHttp() {
        var gateway = new Gateway();
        var embeddings = new LocalEmbeddingService() {
            @Override public boolean isAvailable() { return true; }
            @Override public Map<String, Double> scoreNodesForAnalysis(String text, List<TaxonomyNode> nodes) {
                return Map.of("BP", 20d);
            }
        };
        AnalysisResult result = service(LlmProvider.LOCAL_ONNX, false, gateway, embeddings)
                .analyzeWithBudget("requirement");
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(20d, result.getRawScores().get("BP"));
        assertTrue(gateway.prompts.isEmpty());
    }

    @Test
    void frozenEmbeddingRootUsesOnlyFrozenCandidateVectorsAndRetainsIndependentTwenty() {
        var embeddings = new FrozenEmbeddings(false);
        var gateway = new Gateway();
        try (var ignored = frozen(embeddings)) {
            embeddings.validateFrozenModel();
            AnalysisResult result = service(LlmProvider.LOCAL_ONNX, false, gateway, embeddings)
                    .analyzeWithBudget("requirement");
            assertEquals("SUCCESS", result.getStatus());
            assertEquals(1, result.getRawScores().size());
            assertEquals(20d, result.getRawScores().get("BP"), 0.00001d);
            assertEquals(List.of("query: requirement", "frozen root text"), embeddings.texts);
            assertTrue(gateway.prompts.isEmpty());
        }
    }

    @Test
    void failedFrozenInferenceRemainsAnExplicitPartialAssessmentWithNoInventedZero() {
        var embeddings = new FrozenEmbeddings(true);
        try (var ignored = frozen(embeddings)) {
            embeddings.validateFrozenModel();
            AnalysisResult result = service(LlmProvider.LOCAL_ONNX, false, new Gateway(), embeddings)
                    .analyzeWithBudget("requirement");
            assertEquals("PARTIAL", result.getStatus());
            assertTrue(result.getRawScores().isEmpty());
            assertFalse(result.getWarnings().isEmpty());
        }
    }

    private static FrozenCatalogueContext.Scope frozen(FrozenEmbeddings embeddings) {
        return frozen(List.of(node("BP", null, "BP")), embeddings);
    }

    private static FrozenCatalogueContext.Scope frozen(List<TaxonomyNode> nodes, FrozenEmbeddings embeddings) {
        var source = new CatalogueSourceIdentity("repo", "workspace", "branch", "a".repeat(40));
        var input = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        var provenance = new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001",
                java.time.Instant.EPOCH, input, input, input);
        var snapshot = new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, "BP",
                nodes.stream().map(node -> RootCatalogueSnapshot.Node.capture(node,
                        new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false)).toList(),
                new CatalogueOverlayService.OverlayMetadata(false, "fixture", "fixture", "v1", null, 1), provenance);
        if (embeddings != null) snapshot = snapshot.withEmbeddings(new RootEmbeddingSnapshot(RootEmbeddingSnapshot.SCHEMA_VERSION, source, "BP",
                        source.sourceCommit(), RootEmbeddingSnapshot.TEXT_VERSION, embeddings.embeddingIdentity(),
                        Map.of("BP", "frozen root text")));
        return new CatalogueSnapshotService(null, null, new CatalogueRuntimePolicy("worker", "BP"), null)
                .bind(source, java.util.Set.of("BP"), List.of(snapshot));
    }

    private static final class FrozenEmbeddings extends LocalEmbeddingService {
        final List<String> texts = new ArrayList<>();
        final boolean fail;
        FrozenEmbeddings(boolean fail) {
            this.fail = fail;
            ReflectionTestUtils.setField(this, "embeddingEnabled", true);
            ReflectionTestUtils.setField(this, "queryPrefix", "query: ");
        }
        @Override public EmbeddingModelIdentity embeddingIdentity() {
            return new EmbeddingModelIdentity("a".repeat(64), "b".repeat(64), Map.of(),
                    "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
        }
        @Override public float[] embed(String text) {
            if (fail) throw new IllegalStateException("Fixture model inference failed");
            texts.add(text);
            float[] vector = new float[384];
            if (text.startsWith("query:")) vector[0] = 1;
            else { vector[0] = .2f; vector[1] = (float) Math.sqrt(.96); }
            return vector;
        }
    }

    @Test
    void singletonChildStillConsumesItsParentsTwentyPointBudget() {
        var gateway = new Gateway("{\"BP\":20}", "{\"BP-1\":5}");
        AnalysisResult result = service(LlmProvider.OPENAI, true, gateway, null).analyzeWithBudget("requirement");
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(20d, result.getRawScores().get("BP"));
        assertEquals(20d, result.getRawScores().get("BP-1"));
        assertEquals(2, gateway.prompts.size());
    }

    @Test
    void recoveredRootQuestionUsesIndependentSemanticsAndReplaysTwenty() {
        var gateway = new Gateway("{\"BP\":20}");
        var service = service(LlmProvider.OPENAI, false, gateway, null);
        var checkpoints = new java.util.LinkedHashMap<String, AnalysisCheckpointSession.Checkpoint>();
        var store = new AnalysisCheckpointSession.Store() {
            @Override public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) {
                return checkpoints.getOrDefault(q.key(),
                        new AnalysisCheckpointSession.Checkpoint(q, "ATTEMPT", null));
            }
            @Override public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) {
                checkpoints.put(q.key(), new AnalysisCheckpointSession.Checkpoint(q, state, detail));
            }
            @Override public void checkActive() { }
        };
        try (var session = new AnalysisCheckpointSession(store)) {
            assertEquals(20d, service.analyzeWithBudget("requirement").getRawScores().get("BP"));
            assertEquals(20d, service.analyzeWithBudget("requirement").getRawScores().get("BP"));
        }
        assertEquals(1, gateway.prompts.size(), "successful assessment must replay, not call again");
        assertEquals(1, checkpoints.size());
        var question = checkpoints.values().iterator().next().question();
        assertEquals(AnalysisCheckpointSession.digest(
                com.taxonomy.dto.AnalysisPercentages.POLICY, "ROOT_RELEVANCE", "BP"), question.key());
        assertTrue(question.prompt().contains("independent relevance"));
    }

    private static final class Capture implements AnalysisEventCallback {
        String status;
        Map<String, Double> scores;
        AnalysisCoverage coverage;
        final List<LlmCallDetail> details = new ArrayList<>();
        @Override public void onPhase(String message, int progressPercent) { }
        @Override public void onScores(Map<String, Double> newScores, Map<String, String> reasons,
                                       String description, LlmCallDetail detail) { details.add(detail); }
        @Override public void onExpanding(String parentCode, List<String> childCodes) { }
        @Override public void onComplete(String status, Map<String, Double> scores, List<String> warnings,
                                         List<TaxonomyDiscrepancy> discrepancies, List<ProductCoverageGap> gaps) {
            this.status = status; this.scores = Map.copyOf(scores);
        }
        @Override public void onComplete(String status, Map<String, Double> scores, List<String> warnings,
                                         List<TaxonomyDiscrepancy> discrepancies, List<ProductCoverageGap> gaps,
                                         AnalysisCoverage coverage) {
            onComplete(status, scores, warnings, discrepancies, gaps);
            this.coverage = coverage;
        }
        @Override public void onError(String status, String message, Map<String, Double> scores,
                                      List<String> warnings, List<TaxonomyDiscrepancy> discrepancies,
                                      List<ProductCoverageGap> gaps) {
            fail(message);
        }
    }
    @Test
    void languagePolicySeparatesNewCheckpointIdentitiesWithoutChangingLegacyIdentity() {
        var llm = service(LlmProvider.OPENAI, false, new Gateway(), null);
        String legacy = llm.recoveryPolicyFingerprint("OPENAI");
        String german;
        try (var scope = AnalysisLanguageContext.open(com.taxonomy.dto.AnalysisLanguage.GERMAN)) {
            german = llm.recoveryPolicyFingerprint("OPENAI");
            assertNotEquals(legacy, german);
        }
        try (var scope = AnalysisLanguageContext.open(com.taxonomy.dto.AnalysisLanguage.ENGLISH)) {
            assertNotEquals(german, llm.recoveryPolicyFingerprint("OPENAI"));
            assertNotEquals(legacy, llm.recoveryPolicyFingerprint("OPENAI"));
        }
        try (var legacyScope = AnalysisLanguageContext.open(null)) {
            assertEquals(legacy, llm.recoveryPolicyFingerprint("OPENAI"));
        }
    }

}
