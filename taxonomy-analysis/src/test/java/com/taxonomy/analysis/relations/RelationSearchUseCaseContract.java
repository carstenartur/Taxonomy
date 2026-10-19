package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.architecture.pipeline.*;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.workspace.service.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.taxonomy.analysis.relations.RelationSearchContract.check;

/** Real use case, search service, parser, core and projection; small catalogue/remote boundaries. */
public final class RelationSearchUseCaseContract {
    static final String ORIGINAL = RequirementRelationSearchContract.READ;
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var m : RelationSearchUseCaseContract.class.getDeclaredMethods()) if (m.getName().startsWith("test")) {
            try { m.invoke(null); passed++; } catch (ReflectiveOperationException ex) { failed.add(m.getName() + ": " + ex.getCause()); }
        }
        failed.forEach(System.err::println);
        System.out.println("Relation use-case contracts: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(failed.size() + " failed");
    }
    public static void testDefaultSearchIsWiredIntoRealUseCase() throws Exception {
        Fixture f = fixture(null, 24, false);
        var result = f.analyze(true);
        check(result.getRelationSearchReport() != null && !result.getRelationSearchReport().result().edges().isEmpty(),
                "Default production use case must execute scoped search without a test enabling it");
        check(f.legacy.get() == 0 && f.legacyViews.get() == 0, "Default must not use score-only inference");
    }
    public static void testDefaultMalformedResponseNeverFallsBackToScores() throws Exception {
        Fixture f = fixture(null, 24, true);
        var result = f.analyze(true);
        check("PARTIAL".equals(result.getStatus()) && result.getRelationSearchReport() != null,
                "Default failure preserves explicit partial evidence");
        check(f.legacy.get() == 0 && f.legacyViews.get() == 0, "No default-error fallback to legacy relations");
    }
    public static void testEnabledSearchIsWiredIntoRealUseCase() throws Exception {
        Fixture f = fixture(true, 24, false);
        var result = f.analyze(true);
        check(result.getRelationSearchReport() != null && !result.getRelationSearchReport().result().edges().isEmpty(), "real use case must execute scoped search");
        check(result.getArchitectureView().getIncludedRelationships().size() == 1, "real evidence projection");
        check(result.getProvisionalRelations().isEmpty(), "scoped relations must not be inserted as global hypotheses");
        check(result.getScores().equals(Map.of("process", 1d)), "original scores remain unchanged");
    }
    public static void testEnabledSearchNeverFallsBackToLegacyInference() throws Exception {
        Fixture f = fixture(true, 24, false); f.analyze(true);
        check(f.legacy.get() == 0 && f.legacyViews.get() == 0, "no legacy generator or propagating view pipeline");
    }
    public static void testZeroBudgetMakesAnalysisPartialAndRetainsReport() throws Exception {
        Fixture f = fixture(true, 0, false); var r = f.analyze(true);
        check(r.getRelationSearchReport() != null && "PARTIAL".equals(r.getStatus()) && f.raw.get() == 0, "explicit partial with no remote call");
        check(r.getArchitectureView().getIncludedRelationships().isEmpty() && !r.getWarnings().isEmpty(), "no fallback for insufficient budget");
    }
    public static void testEmbeddingOnlyProviderLeavesRelationsUnassessedWithoutCallingRawCompletion() throws Exception {
        Fixture f = fixture(true, 24, false, LlmProvider.LOCAL_ONNX);
        AnalysisResult result = f.analyze(true);
        check("PARTIAL".equals(result.getStatus()), "embedding-only relation evidence must be incomplete");
        check(result.getRelationSearchReport() != null && !result.getRelationSearchReport().isSearchExhausted()
                        && result.getRelationSearchReport().stopReason().contains("UNSUPPORTED"),
                "immutable report must explain the unsupported generative assessment");
        check(result.getRelationSearchReport().result().edges().isEmpty()
                        && result.getArchitectureView().getIncludedRelationships().isEmpty(),
                "no verified relationships may be inferred from local similarity scores");
        check(f.raw.get() == 0 && f.legacy.get() == 0,
                "unsupported raw completion must not be called or retried, nor fall back to score products");
        var json = JsonMapper.builder().build();
        AnalysisResult saved = json.readValue(json.writeValueAsString(result), AnalysisResult.class);
        check("PARTIAL".equals(saved.getStatus()) && saved.getRelationSearchReport() != null
                        && saved.getRelationSearchReport().stopReason().contains("UNSUPPORTED"),
                "provider limitation must survive the snapshot export boundary");
    }
    public static void testMalformedResponsePreservesPartialEvidence() throws Exception {
        Fixture f = fixture(true, 24, true); var r = f.analyze(true);
        check("PARTIAL".equals(r.getStatus()) && r.getRelationSearchReport() != null && !r.getWarnings().isEmpty(), "parse failure visible");
        check(r.getArchitectureView().getIncludedRelationships().isEmpty() && f.legacy.get() == 0, "no invented relations on parser failure");
    }
    public static void testDisabledModeRetainsExistingBehaviourWithoutExtraCalls() throws Exception {
        Fixture f = fixture(false, 24, false); var r = f.analyze(true);
        check(r.getRelationSearchReport() == null && f.raw.get() == 0 && f.legacy.get() == 1 && f.legacyViews.get() == 1, "legacy override is explicit");
    }
    public static void testNoArchitectureStillRetainsScopedReport() throws Exception {
        Fixture f = fixture(true, 24, false); var r = f.analyze(false);
        check(r.getRelationSearchReport() != null && r.getArchitectureView() == null, "analysis evidence independent from diagram request");
    }
    public static void testAnalysisSnapshotRetainsStructuredRelationEvidence() throws Exception {
        var r = fixture(true, 24, false).analyze(true);
        var json = JsonMapper.builder().build();
        AnalysisResult copy = json.readValue(json.writeValueAsString(r), AnalysisResult.class);
        check(copy.getRelationSearchReport() != null && !copy.getRelationSearchReport().result().edges().isEmpty(), "snapshot report retained");
        check(!copy.getArchitectureView().getIncludedRelationships().getFirst().getRequirementEvidence().isEmpty(), "view evidence retained");
    }
    public static void testProjectAnalysisContinuesBeyondHigherScoredContextSources() throws Exception {
        var prompts = new ArrayList<String>();
        var f = sourceCohortFixture(24, prompts, false, false);
        var result = f.analyzeProject();
        var report = result.getRelationSearchReport();
        check("SUCCESS".equals(result.getStatus()) && report.isSearchExhausted(),
                "A project analysis must finish the required low-share source beyond the first 32 candidates");
        check(report.progress().totalSources() == 33 && report.progress().assessedSources() == 33,
                "Every positive candidate, including the 0.5-share leaf, is assessed");
        check(report.progress().pendingSearches() == 0 && report.progress().completedSearches() == 33,
                "A complete result must have no unassessed source/target tasks");
        check(report.result().edges().size() == 1 && report.result().edges().getFirst().sourceId().equals("process")
                        && report.result().edges().getFirst().targetId().equals("evidence"),
                "The late source contributes its separately verified evidence relation");
        check(result.getArchitectureView().getIncludedRelationships().size() == 1
                        && result.getScores().get("process") == 0.5,
                "The relation reaches the real architecture projection without changing the allocated score");
        check(new HashSet<>(prompts).size() == prompts.size() && report.totalCalls() == prompts.size(),
                "Source continuation reuses successful answers and reports all actual calls");
        check(report.totalCalls() <= 24 && report.maxCalls() == 24,
                "Cohorts share the original call budget");
        check(!com.taxonomy.analysis.recovery.AnalysisCheckpointSession.active(),
                "The request-local replay session must not leak into another project analysis");
    }
    public static void testVerificationPassesCannotResetTheBudgetOrClaimPendingSourcesComplete() throws Exception {
        var prompts = new ArrayList<String>();
        var f = sourceCohortFixture(10, prompts, false, false);
        for (int pass = 0; pass < 2; pass++) {
            prompts.clear();
            var result = f.analyzeProject();
            var report = result.getRelationSearchReport();
            check("PARTIAL".equals(result.getStatus()) && !report.isSearchExhausted(),
                    "An independent verification job does not complete unassessed source work");
            check(prompts.size() == 10 && report.totalCalls() == 10 && report.maxCalls() == 10,
                    "No source continuation may renew the caller's total call budget");
            check(report.progress().assessedSources() == 30 && report.progress().totalSources() == 33
                            && report.progress().pendingSearches() == 3,
                    "Budget exhaustion retains the exact unassessed sources in the fixed task plan");
            check(result.getErrorMessage().contains("3 unassessed sources"),
                    "A partial result explains unassessed sources even with no unfinished navigation batches");
            check(report.result().edges().isEmpty(), "The unassessed late source cannot fabricate an edge");
        }
    }
    public static void testSourceCohortsShareTheCallBudgetAfterCrossingTheSourceLimit() throws Exception {
        var prompts = new ArrayList<String>();
        var result = sourceCohortFixture(14, prompts, false, false).analyzeProject();
        var report = result.getRelationSearchReport();
        check(prompts.size() == 14 && report.totalCalls() == 14 && report.maxCalls() == 14,
                "The second source cohort consumes the three remaining calls, not a fresh budget");
        check(report.progress().assessedSources() == 33 && report.progress().completedSearches() == 32
                        && report.progress().unresolvedSearches() == 1,
                "Source coverage does not mark an unfinished verification task complete");
        check("PARTIAL".equals(result.getStatus()) && !report.isSearchExhausted()
                        && report.result().edges().isEmpty(),
                "A navigation match without a budgeted verification stays partial");
        check(new HashSet<>(prompts).size() == prompts.size(), "No accepted first-cohort request is sent twice");
    }
    public static void testSourceContinuationRetainsUnresolvedEvidenceWithoutRetryingIt() throws Exception {
        var prompts = new ArrayList<String>();
        var result = sourceCohortFixture(24, prompts, true, false).analyzeProject();
        var report = result.getRelationSearchReport();
        check("PARTIAL".equals(result.getStatus()) && !report.isSearchExhausted(),
                "A source clarification must remain partial after later sources have been assessed");
        check(report.progress().assessedSources() == 33 && report.progress().unresolvedSearches() == 1,
                "Later source coverage does not erase the unresolved contribution");
        check(report.sources().stream().anyMatch(source -> source.node().id().equals("context-00")
                        && source.question().equals("Which administrative function is requested?")),
                "The original clarification remains reviewable");
        check(new HashSet<>(prompts).size() == prompts.size(), "A clarification is not retried automatically");
    }
    public static void testCancellationStopsSourceContinuationAndKeepsAssessedSources() throws Exception {
        var prompts = new ArrayList<String>();
        var result = sourceCohortFixture(24, prompts, false, true).analyzeProject();
        var report = result.getRelationSearchReport();
        check("PARTIAL".equals(result.getStatus()) && report.stopReason().startsWith("CANCELLED"),
                "Cancellation remains an explicit stop, not a new source cohort");
        check(prompts.size() == 12 && report.progress().assessedSources() == 32,
                "No call follows cancellation while the first cohort's evidence remains available");
        check(report.result().edges().isEmpty(), "The interrupted late source remains unassessed");
    }
    private static Fixture sourceCohortFixture(int calls, List<String> prompts,
                                               boolean unresolved, boolean cancelLateSource) throws Exception {
        var nodes = new LinkedHashMap<String, TaxonomyNode>();
        var scores = new LinkedHashMap<String, Double>();
        nodes.put("BP", node("BP", "BP", null));
        nodes.put("IP", node("IP", "IP", null));
        nodes.put("evidence", node("evidence", "IP", "IP"));
        String parent = "BP";
        for (int n = 0; n < 32; n++) {
            String id = "context-%02d".formatted(n);
            nodes.put(id, node(id, "BP", parent));
            scores.put(id, 100d);
            parent = id;
        }
        nodes.put("process", node("process", "BP", parent));
        scores.put("process", 0.5);
        var catalogue = new TaxonomyService(null, null, null) {
            @Override public TaxonomyNode getNodeByCode(String id) { return nodes.get(id); }
            @Override public List<TaxonomyNode> getRootNodes() { return List.of(nodes.get("IP")); }
            @Override public List<TaxonomyNode> getChildrenOf(String id) {
                return id.equals("IP") ? List.of(nodes.get("evidence")) : List.of();
            }
        };
        return fixture(true, calls, LlmProvider.CUSTOM_OPENAI, prompt -> {
            prompts.add(prompt);
            var json = JsonMapper.builder().build();
            var input = json.readTree(prompt.substring(prompt.lastIndexOf("INPUT\n") + 6));
            if (!input.has("nodes")) return RequirementRelationSearchContract.answer(prompt);
            var selections = new ArrayList<Map<String, Object>>();
            for (var offered : input.get("nodes")) {
                String id = offered.path("id").asString();
                if (id.equals("process")) {
                    if (cancelLateSource) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                    selections.add(Map.of("nodeId", id, "outcome", "EXPLICIT", "contributions",
                            List.of(Map.of("text", "read existing evidence", "quote", ORIGINAL, "condition", "")),
                            "rationale", "The requirement explicitly requests evidence access", "question", ""));
                } else {
                    boolean clarify = unresolved && id.equals("context-00");
                    selections.add(Map.of("nodeId", id, "outcome", clarify ? "UNRESOLVED" : "REJECT",
                            "contributions", List.of(), "rationale", "The broad ancestor has no distinct requested function",
                            "question", clarify ? "Which administrative function is requested?" : ""));
                }
            }
            return json.writeValueAsString(Map.of("selections", selections));
        }, scores, catalogue, true);
    }
    static Fixture fixture(Boolean enabled, int calls, boolean invalid) throws Exception {
        return fixture(enabled, calls, invalid, LlmProvider.CUSTOM_OPENAI);
    }
    static Fixture fixture(Boolean enabled, int calls, boolean invalid, LlmProvider provider) throws Exception {
        return fixture(enabled, calls, provider,
                prompt -> invalid ? "please try again" : RequirementRelationSearchContract.answer(prompt));
    }
    static Fixture fixture(Boolean enabled, int calls, LlmProvider provider,
                           java.util.function.Function<String, String> complete) throws Exception {
        TaxonomyNode source = node("process", "BP", "BP"), root = node("IP", "IP", null), target = node("evidence", "IP", "IP");
        TaxonomyService catalogue = new TaxonomyService(null, null, null) {
            @Override public TaxonomyNode getNodeByCode(String id) { return Map.of("process", source, "BP", node("BP", "BP", null), "IP", root, "evidence", target).get(id); }
            @Override public List<TaxonomyNode> getRootNodes() { return List.of(root); }
            @Override public List<TaxonomyNode> getChildrenOf(String id) { return id.equals("IP") ? List.of(target) : List.of(); }
        };
        return fixture(enabled, calls, provider, complete, Map.of("process", 1d), catalogue);
    }
    private static Fixture fixture(Boolean enabled, int calls, LlmProvider provider,
                                   java.util.function.Function<String, String> complete, Map<String, Double> scores,
                                   TaxonomyService catalogue) throws Exception {
        return fixture(enabled, calls, provider, complete, scores, catalogue, false);
    }
    private static Fixture fixture(Boolean enabled, int calls, LlmProvider provider,
                                   java.util.function.Function<String, String> complete, Map<String, Double> scores,
                                   TaxonomyService catalogue, boolean continueSources) throws Exception {
        AtomicInteger raw = new AtomicInteger(), legacy = new AtomicInteger(), views = new AtomicInteger();
        LlmService llm = new LlmService(null, null, JsonMapper.builder().build(), null, null, null, null) {
            @Override public AnalysisResult analyzeWithBudget(String original) { var r = new AnalysisResult(); r.setScores(scores); r.setStatus("SUCCESS"); return r; }
            @Override public String callLlmRaw(String prompt) { raw.incrementAndGet(); return complete.apply(prompt); }
            @Override public String callLlmRaw(String prompt, com.taxonomy.analysis.service.LlmCompletionOptions options) { return callLlmRaw(prompt); }
            @Override public String getActiveProviderName() { return "TEST"; }
            @Override public com.taxonomy.extension.api.llm.ProviderId getActiveProviderId() { return provider.id(); }
            @Override public void clearRequestProvider() { }
        };
        AiPromptBudgetPolicy policy = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(String text, String provider) { return null; }
        };
        var service = new RequirementRelationSearchService(catalogue, new RelationCompatibilityMatrix(), llm, policy);
        if (enabled != null) field(service, "enabled", enabled);
        field(service, "maxCalls", calls);
        // These contracts deliberately retain their small total allowance and
        // original cohort boundary while production defaults are configurable.
        field(service, "maxTotalCalls", calls);
        field(service, "maxSources", 32);
        field(service, "autoContinue", continueSources);
        var generator = new AnalysisRelationGenerator(new RelationCompatibilityMatrix(), id -> Optional.empty()) {
            @Override public List<RelationHypothesisDto> generate(Map<String,Double> scores) { legacy.incrementAndGet(); return List.of(); }
        };
        var projection = new RequirementArchitectureViewService(new ArchitectureViewPipeline(null, new ArchitecturePipelineInvariantValidator())) {
            @Override public RequirementArchitectureView build(Map<String,Double> scores, String text, int limit, List<RelationHypothesisDto> provisional) {
                views.incrementAndGet(); return new RequirementArchitectureView();
            }
        };
        WorkspaceViewContextReadPort contexts = new WorkspaceViewContextReadPort() {
            public String resolveWorkspaceBranch(String username) { return "draft"; }
            public ViewContext getViewContext(String username, String branch, WorkspaceContext context) { return null; }
        };
        AnalyzeRequirementUseCase useCase = new AnalyzeRequirementUseCase(llm, policy, projection, generator, null, contexts,
                () -> DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        field(useCase, "requirementRelationSearch", service);
        return new Fixture(useCase, raw, legacy, views);
    }
    private static TaxonomyNode node(String id, String root, String parent) {
        var n = new TaxonomyNode(); n.setCode(id); n.setNameEn(id); n.setTaxonomyRoot(root); n.setParentCode(parent); return n;
    }
    private static void field(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    record Fixture(AnalyzeRequirementUseCase useCase, AtomicInteger raw, AtomicInteger legacy, AtomicInteger legacyViews) {
        AnalysisResult analyze(boolean architecture) {
            return analyze(architecture, null);
        }
        AnalysisResult analyzeProject() {
            return analyze(true, new AnalysisProvenance(1L, 1L, "snapshot", "portfolio:snapshot"));
        }
        private AnalysisResult analyze(boolean architecture, AnalysisProvenance provenance) {
            return useCase.analyze(new AnalyzeRequirementCommand(ORIGINAL, architecture, 20, null, "tester",
                    new WorkspaceContext("tester", "test-workspace", "draft"), provenance)).analysisResult();
        }
    }
}
