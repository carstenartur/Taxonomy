package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.relations.*;
import com.taxonomy.architecture.pipeline.*;
import com.taxonomy.architecture.service.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.workspace.service.*;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Real use case and projection, with only provider/repository boundaries controlled. */
public final class PartialArchitectureChecks {
    private PartialArchitectureChecks() { }
    public static void main(String[] args) throws Exception {
        int passed = 0; List<String> failed = new ArrayList<>();
        for (var method : Arrays.stream(PartialArchitectureChecks.class.getDeclaredMethods())
                .filter(m -> m.getName().startsWith("test")).sorted(Comparator.comparing(java.lang.reflect.Method::getName)).toList()) {
            try { method.invoke(null); passed++; System.out.println("PASS " + method.getName()); }
            catch (java.lang.reflect.InvocationTargetException error) { failed.add(method.getName() + ": " + error.getCause()); }
        }
        System.out.println("Partial architecture: " + passed + " passed, " + failed.size() + " failed");
        if (!failed.isEmpty()) throw new AssertionError(String.join("\n", failed));
    }
    public static void testAwaitingDecisionStillProjectsAcceptedNodes() throws Exception {
        var input = assessed(); input.setStatus("PARTIAL"); input.setErrorMessage("AWAITING_DECISION: uncertain answer");
        var result = fixture(input, null).analyze(command(true)).analysisResult();
        assertView(result, 2); check(result.getRawScores().equals(input.getRawScores()), "scoring evidence unchanged");
    }
    public static void testRelationStopStillProjectsAcceptedNodes() throws Exception {
        var useCase = fixture(assessed(), null);
        field(useCase, "requirementRelationSearch", new RequirementRelationSearchService(null, null, null, null) {
            @Override public RelationSearchReport search(String original, Map<String,Integer> scores) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.TIME_LIMIT);
            }
        });
        var result = useCase.analyze(command(true)).analysisResult();
        assertView(result, 2); check(result.getStatus().equals("PARTIAL"), "stop remains visible");
    }
    public static void testUnexpectedRelationFailureStillProjectsAcceptedNodes() throws Exception {
        var useCase = fixture(assessed(), null);
        field(useCase, "requirementRelationSearch", new RequirementRelationSearchService(null, null, null, null) {
            @Override public RelationSearchReport search(String original, Map<String,Integer> scores) {
                throw new IllegalStateException("relation preparation unavailable");
            }
        });
        var result = useCase.analyze(command(true)).analysisResult();
        assertView(result, 2); check(result.getStatus().equals("PARTIAL"), "relation failure cannot claim completeness");
        check(result.getArchitectureView().getIncludedRelationships().isEmpty(), "never fall back to inferred relations");
    }
    public static void testAllSourceExtractionFailuresStillShowScoredNodes() throws Exception {
        var result = fixture(assessed(), null).analyze(command(true)).analysisResult();
        assertView(result, 2);
        check(result.getArchitectureView().getIncludedRelationships().isEmpty(), "no score-inferred relations");
        check(result.getArchitectureView().getRelationSearchReport() == result.getRelationSearchReport(), "full failed relation report retained");
    }
    public static void testUnexpectedRenderingFailureUsesBoundedFallback() throws Exception {
        var result = fixture(assessed(), new IllegalStateException("renderer unavailable")).analyze(command(true)).analysisResult();
        assertView(result, 2); check(result.getStatus().equals("PARTIAL"), "fallback is not an unqualified success");
        check(result.getWarnings().stream().anyMatch(w -> w.startsWith("ARCHITECTURE_VIEW_INCOMPLETE")), "explain fallback");
    }
    public static void testDatabaseFailureIsNotConvertedToSuccessfulRendering() throws Exception {
        try {
            fixture(assessed(), new DataAccessResourceFailureException("database offline")).analyze(command(true));
            throw new AssertionError("database failure was swallowed");
        } catch (DataAccessResourceFailureException expected) { }
    }
    public static void testViewLimitAndContainerExclusion() throws Exception {
        var small = new AnalyzeRequirementCommand("requirement", true, 1, null, "tester", scope());
        var result = fixture(assessed(), null).analyze(small).analysisResult();
        assertView(result, 1);
        check(result.getArchitectureView().getIncludedElements().getFirst().getNodeCode().equals("BP-1"), "highest assessed concrete node first, not BP container");
    }
    public static void testNoRequestedViewRemainsAbsent() throws Exception {
        check(fixture(assessed(), null).analyze(command(false)).analysisResult().getArchitectureView() == null, "do not invent an unrequested view");
    }
    public static void testEmptyEvidenceHasAnHonestEmptyView() throws Exception {
        var input = assessed(); input.setRawScores(Map.of());
        var result = fixture(input, null).analyze(command(true)).analysisResult();
        assertView(result, 0);
        check(result.getArchitectureView().getNotes().stream().anyMatch(n -> n.startsWith("NO_ASSESSED_ARCHITECTURE")), "explicit empty state");
    }
    public static void testSerializedFallbackRetainsCoverageAndStatus() throws Exception {
        var result = fixture(assessed(), null).analyze(command(true)).analysisResult();
        var json = new ObjectMapper();
        var saved = json.readValue(json.writeValueAsString(result), AnalysisResult.class);
        assertView(saved, 2); check(saved.getArchitectureView().getAnalysisStatus().equals("PARTIAL"), "snapshot retains partial status");
    }
    public static void testDistributedDirectViewUsesOnlyRetainedAssessedCatalogueElements() throws Exception {
        var method = com.taxonomy.analysis.cluster.FrozenClusterAnalysisFinalizer.class
                .getDeclaredMethod("scoredView", AnalyzeRequirementCommand.class, AnalysisResult.class);
        method.setAccessible(true);
        var result = assessed();
        // The finalizer's source authority has already been validated; the renderer
        // needs no new thread-local catalogue read to show the retained result.
        var view = (RequirementArchitectureView) method.invoke(null, command(true), result);
        check(view.getIncludedElements().size() == 2, "navigation containers must not become architecture elements");
        check(view.getIncludedRelationships().isEmpty(), "no invented relationships in the direct projection");
        check(result.getRawScores().equals(Map.of("BP",100,"BP-1",70,"BP-2",30)), "frozen scores remain unchanged");
    }
    public static void testFallbackPreservesAcceptedRelationshipsWithoutInferringNewOnes() {
        var result = assessed(); var view = new RequirementArchitectureView();
        for (String code : List.of("BP-1","BP-2")) {
            var element = new RequirementElementView(); element.setNodeCode(code); element.setTitle(code);
            view.getIncludedElements().add(element);
        }
        var edge = new RequirementRelationshipView(); edge.setSourceCode("BP-1"); edge.setTargetCode("BP-2"); edge.setRelationType("DEPENDS_ON");
        view.getIncludedRelationships().add(edge);
        var projected = PartialArchitectureProjection.complete(result,view,20);
        check(projected.getIncludedRelationships().size()==1 && projected.getIncludedRelationships().getFirst()==edge,
                "a fallback must preserve previously accepted relation objects, not replace them with score inference");
        check(projected.getIncludedElements().size()==2 && projected.getTotalRelationships()==1, "no duplicate elements or edges");
    }
    public static void testMalformedHierarchyDoesNotIntroduceAmbiguousNodesOrCyclicContainment() {
        var result = assessed(); var duplicate = new TaxonomyNodeDto(); duplicate.setCode("BP-1"); duplicate.setNameEn("Conflicting title");
        duplicate.setTaxonomyRoot("BP"); duplicate.setParentCode("BP"); duplicate.setLevel(1);
        result.getTree().getFirst().getChildren().add(duplicate);
        var view = PartialArchitectureProjection.complete(result,null,20);
        check(view.getIncludedElements().size()==1 && view.getIncludedElements().getFirst().getNodeCode().equals("BP-2"), "ambiguous identities are not relabelled");
        var cycle = assessed(); var children = cycle.getTree().getFirst().getChildren();
        children.get(0).setParentCode("BP-2"); children.get(1).setParentCode("BP-1");
        var cyclic = PartialArchitectureProjection.complete(cycle,null,20);
        check(cyclic.getIncludedElements().size()==2 && cyclic.getIncludedElements().stream().allMatch(node -> node.getParentNodeCode()==null),
                "independent assessments can be shown without reproducing an invalid containment cycle");
    }
    public static void testLongContainmentCycleCannotBypassProjectionDepthLimit() {
        var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setTaxonomyRoot("BP");
        var scores = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < 300; i++) {
            var child = new TaxonomyNodeDto(); child.setCode("BP-CYCLE-" + i);
            child.setTaxonomyRoot("BP"); child.setLevel(1);
            child.setParentCode("BP-CYCLE-" + ((i + 1) % 300));
            root.getChildren().add(child); scores.put(child.getCode(), 1);
        }
        var result = new AnalysisResult(scores, List.of(root));
        var projected = PartialArchitectureProjection.complete(result, null, 400);
        check(projected.getIncludedElements().size() == 300, "retain all independently assessed cycle nodes");
        check(projected.getIncludedElements().stream().allMatch(node -> node.getParentNodeCode() == null),
                "a containment cycle longer than the ancestry limit must not reappear in the view");
        check(projected.getNotes().stream().anyMatch(note -> note.startsWith("HIERARCHY_PROJECTION_LIMIT:")),
                "an incomplete ancestry check must be explicit, not presented as verified containment");
        check(result.getRawScores().equals(scores), "projection cannot change accepted score evidence");
    }

    static AnalyzeRequirementUseCase fixture(AnalysisResult input, RuntimeException renderingFailure) throws Exception {
        var llm = new LlmService(null, null, new ObjectMapper(), null, null, null, null) {
            @Override public void requireProviderAdmissible(String provider, boolean background) { }
            @Override public AnalysisResult analyzeWithBudget(String original) { return input; }
            @Override public void clearRequestProvider() { }
        };
        var budget = new AiPromptBudgetPolicy(null) {
            @Override public com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor requireWithinBudget(String text, String provider) { return null; }
        };
        var architecture = new RequirementArchitectureViewService(new ArchitectureViewPipeline(null, new ArchitecturePipelineInvariantValidator())) {
            @Override public RequirementArchitectureView buildFromEvidence(Map<String,Integer> scores, Map<String,AnalysisScoreDetail> details, int limit, RelationSearchReport report) {
                if (renderingFailure != null) throw renderingFailure;
                return super.buildFromEvidence(scores, details, limit, report);
            }
        };
        WorkspaceViewContextReadPort views = new WorkspaceViewContextReadPort() {
            public String resolveWorkspaceBranch(String username) { return "draft"; }
            public ViewContext getViewContext(String username, String branch, WorkspaceContext context) { return null; }
        };
        var useCase = new AnalyzeRequirementUseCase(llm, budget, architecture, null, null, views,
                () -> DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        field(useCase, "requirementRelationSearch", new RequirementRelationSearchService(null,null,null,null) {
            @Override public RelationSearchReport search(String original, Map<String,Integer> scores) {
                return RequirementRelationSearch.unassessed(original, 24, "INVALID_SOURCE_RESPONSE");
            }
        });
        return useCase;
    }
    static void field(Object object, String field, Object value) throws Exception {
        var f = object.getClass().getDeclaredField(field); f.setAccessible(true); f.set(object,value);
    }
    static AnalysisResult assessed() {
        var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setTaxonomyRoot("BP"); root.setNameEn("Processes");
        for (String code : List.of("BP-1", "BP-2")) {
            var child = new TaxonomyNodeDto(); child.setCode(code); child.setNameEn(code); child.setNameDe("Prozess " + code);
            child.setTaxonomyRoot("BP"); child.setParentCode("BP"); child.setLevel(1); root.getChildren().add(child);
        }
        var result = new AnalysisResult(Map.of("BP", 100, "BP-1", 70, "BP-2", 30), List.of(root));
        result.setStatus("SUCCESS"); return result;
    }
    static AnalyzeRequirementCommand command(boolean view) { return new AnalyzeRequirementCommand("requirement", view, 20, null, "tester", scope()); }
    static WorkspaceContext scope() { return new WorkspaceContext("tester", "work", "draft", "repo"); }
    static void assertView(AnalysisResult result, int count) {
        check(result.getArchitectureView() != null, "partial architecture must exist");
        check(result.getArchitectureView().getIncludedElements().size() == count,
                "expected " + count + " assessed elements, got " + result.getArchitectureView().getIncludedElements().size());
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
