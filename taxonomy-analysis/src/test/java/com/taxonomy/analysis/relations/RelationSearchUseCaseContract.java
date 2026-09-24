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
    public static void testEnabledSearchIsWiredIntoRealUseCase() throws Exception {
        Fixture f = fixture(true, 24, false);
        var result = f.analyze(true);
        check(result.getRelationSearchReport() != null && !result.getRelationSearchReport().result().edges().isEmpty(), "real use case must execute scoped search");
        check(result.getArchitectureView().getIncludedRelationships().size() == 1, "real evidence projection");
        check(result.getProvisionalRelations().isEmpty(), "scoped relations must not be inserted as global hypotheses");
        check(result.getScores().equals(Map.of("process", 1)), "original scores remain unchanged");
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
    public static void testMalformedResponsePreservesPartialEvidence() throws Exception {
        Fixture f = fixture(true, 24, true); var r = f.analyze(true);
        check("PARTIAL".equals(r.getStatus()) && r.getRelationSearchReport() != null && !r.getWarnings().isEmpty(), "parse failure visible");
        check(r.getArchitectureView().getIncludedRelationships().isEmpty() && f.legacy.get() == 0, "no invented relations on parser failure");
    }
    public static void testDisabledModeRetainsExistingBehaviourWithoutExtraCalls() throws Exception {
        Fixture f = fixture(false, 24, false); var r = f.analyze(true);
        check(r.getRelationSearchReport() == null && f.raw.get() == 0 && f.legacy.get() == 1 && f.legacyViews.get() == 1, "opt-in is explicit");
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
    static Fixture fixture(boolean enabled, int calls, boolean invalid) throws Exception {
        AtomicInteger raw = new AtomicInteger(), legacy = new AtomicInteger(), views = new AtomicInteger();
        LlmService llm = new LlmService(null, null, JsonMapper.builder().build(), null, null, null, null) {
            @Override public AnalysisResult analyzeWithBudget(String original) { var r = new AnalysisResult(); r.setScores(Map.of("process", 1)); r.setStatus("SUCCESS"); return r; }
            @Override public String callLlmRaw(String prompt) { raw.incrementAndGet(); return invalid ? "please try again" : RequirementRelationSearchContract.answer(prompt); }
            @Override public String getActiveProviderName() { return "TEST"; }
            @Override public void clearRequestProvider() { }
        };
        TaxonomyNode source = node("process", "BP", "BP"), root = node("IP", "IP", null), target = node("evidence", "IP", "IP");
        TaxonomyService catalogue = new TaxonomyService(null, null, null) {
            @Override public TaxonomyNode getNodeByCode(String id) { return Map.of("process", source, "BP", node("BP", "BP", null), "IP", root, "evidence", target).get(id); }
            @Override public List<TaxonomyNode> getRootNodes() { return List.of(root); }
            @Override public List<TaxonomyNode> getChildrenOf(String id) { return id.equals("IP") ? List.of(target) : List.of(); }
        };
        AiPromptBudgetPolicy policy = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(String text, String provider) { return null; }
        };
        var service = new RequirementRelationSearchService(catalogue, new RelationCompatibilityMatrix(), llm, policy);
        field(service, "enabled", enabled); field(service, "maxCalls", calls);
        var generator = new AnalysisRelationGenerator(new RelationCompatibilityMatrix(), id -> Optional.empty()) {
            @Override public List<RelationHypothesisDto> generate(Map<String,Integer> scores) { legacy.incrementAndGet(); return List.of(); }
        };
        var projection = new RequirementArchitectureViewService(new ArchitectureViewPipeline(null, new ArchitecturePipelineInvariantValidator())) {
            @Override public RequirementArchitectureView build(Map<String,Integer> scores, String text, int limit, List<RelationHypothesisDto> provisional) {
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
            return useCase.analyze(new AnalyzeRequirementCommand(ORIGINAL, architecture, 20, null, "tester",
                    new WorkspaceContext("tester", "test-workspace", "draft"))).analysisResult();
        }
    }
}
