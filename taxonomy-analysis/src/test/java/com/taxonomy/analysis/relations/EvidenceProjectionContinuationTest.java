package com.taxonomy.analysis.relations;

import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.architecture.pipeline.*;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.*;
import com.taxonomy.export.*;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real use-case, score semantics, projection and JSON boundary; remote analysis is replaced. */
class EvidenceProjectionContinuationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "Read evidence.";
    private static final Node SOURCE = new Node("reader", "UA", "Evidence reader", "", false);
    private static final Node TARGET = new Node("record", "IP", "Evidence record", "", false);

    @Test
    void rawSuitabilitySurvivesRealUseCaseAndSnapshot() throws Exception {
        AnalysisResult result = analyze(List.of(edge(Necessity.REQUIRED, Direction.OUTGOING)), 20, false);
        assertEquals(80, result.getRawScores().get("reader"));
        assertEquals(32, result.getScores().get("reader"));
        RequirementElementView source = element(result, "reader");
        assertEquals(80, source.getDirectLlmScore(), "effective product relevance must not replace the original score");
        assertEquals(0.32, source.getRelevance(), 0.00001);
        var metadata = JSON.readTree(JSON.writeValueAsString(source)).path("scoreDetail");
        assertEquals("PRODUCT_SUITABILITY", metadata.path("kind").asString());
        assertEquals(40, metadata.path("parentScore").asInt());
        AnalysisResult copy = JSON.readValue(JSON.writeValueAsString(result), AnalysisResult.class);
        assertEquals(metadata, JSON.readTree(JSON.writeValueAsString(element(copy, "reader"))).path("scoreDetail"));
        assertEquals(result.getRelationSearchReport(), copy.getRelationSearchReport());
    }

    @Test
    void explicitZeroHasKnownScoreDetail() throws Exception {
        var result = analyze(List.of(edge(Necessity.REQUIRED, Direction.OUTGOING)), 20, true);
        var node = JSON.readTree(JSON.writeValueAsString(element(result, "record")));
        assertEquals(0, node.path("scoreDetail").path("rawScore").asInt(-1), "explicit zero is known evidence");
    }

    @Test
    void unassessedNodeIsSerializedAsUnknownRatherThanAnExplicitZero() throws Exception {
        var result = analyze(List.of(edge(Necessity.REQUIRED, Direction.OUTGOING)), 20, false);
        var node = JSON.readTree(JSON.writeValueAsString(element(result, "record")));
        assertTrue(node.has("scoreDetail"), "the view must carry an explicit score-presence contract");
        assertTrue(node.path("scoreDetail").isNull(), "absence of assessment must remain unknown");
        assertFalse(result.getRawScores().containsKey("record"));
        assertFalse(result.getScores().containsKey("record"));
    }

    @Test
    void choicesOnRequiredSignatureRetainAllEvidenceRegardlessOfArrivalOrder() throws Exception {
        Edge required = edge(Necessity.REQUIRED, Direction.OUTGOING);
        Edge optional = edge(Necessity.OPTIONAL, Direction.OUTGOING);
        Edge alternative = edge(Necessity.ALTERNATIVE, Direction.OUTGOING);
        for (List<Edge> input : List.of(List.of(required, optional, alternative), List.of(alternative, optional, required))) {
            var result = analyze(input, 20, false);
            var view = result.getArchitectureView();
            assertEquals(1, view.getIncludedRelationships().size());
            assertEquals(2, view.getIncludedElements().size());
            assertEquals(Set.of(required, optional, alternative), new HashSet<>(view.getIncludedRelationships().getFirst().getRequirementEvidence()), "same signature must retain separate necessity/condition contexts");
            assertEquals(input, result.getRelationSearchReport().result().edges());
            var copy = JSON.readValue(JSON.writeValueAsString(view), RequirementArchitectureView.class);
            assertEquals(3, copy.getIncludedRelationships().getFirst().getRequirementEvidence().size());
        }
    }

    @Test
    void incomingSignatureRetainsConditionsWithoutReversingEndpoints() throws Exception {
        var input = List.of(edge(Necessity.ALTERNATIVE, Direction.INCOMING), edge(Necessity.REQUIRED, Direction.INCOMING));
        var relationship = analyze(input, 20, false).getArchitectureView().getIncludedRelationships().getFirst();
        assertEquals("record", relationship.getSourceCode());
        assertEquals("reader", relationship.getTargetCode());
        assertEquals(new HashSet<>(input), new HashSet<>(relationship.getRequirementEvidence()));
    }

    @Test
    void pureChoicesDoNotBecomeRequiredEdges() throws Exception {
        var input = List.of(edge(Necessity.OPTIONAL, Direction.OUTGOING), edge(Necessity.ALTERNATIVE, Direction.OUTGOING));
        var result = analyze(input, 20, false);
        assertTrue(result.getArchitectureView().getIncludedRelationships().isEmpty());
        assertTrue(result.getArchitectureView().getIncludedElements().isEmpty());
        assertEquals(input, result.getRelationSearchReport().result().edges());
    }

    @Test
    void nodeLimitDoesNotChangeReportOrLeaveDanglingEdges() throws Exception {
        var input = List.of(edge(Necessity.REQUIRED, Direction.OUTGOING));
        var result = analyze(input, 1, false);
        assertTrue(result.getArchitectureView().getIncludedRelationships().isEmpty());
        assertTrue(result.getArchitectureView().getNotes().stream().anyMatch(note -> note.contains("NODE_LIMIT")));
        assertEquals(input, result.getRelationSearchReport().result().edges());
    }

    @Test
    void neutralDiagramDoesNotIntroduceOptionalOnlyEndpoints() throws Exception {
        var optionalNode = new Node("optional", "IP", "Optional evidence", "", false);
        var optional = new Edge(edge(Necessity.REQUIRED, Direction.OUTGOING).contribution(), optionalNode, "CONSUMES", Direction.OUTGOING,
                new Decision("optional", Outcome.VERIFIED, "optional record", ORIGINAL, Necessity.OPTIONAL, "if requested", "", "optional", ""));
        var result = analyze(List.of(edge(Necessity.REQUIRED, Direction.OUTGOING), optional), 20, false);
        var diagram = new DiagramProjectionService().projectRaw(result.getArchitectureView(), "Evidence");
        assertEquals(Set.of("reader", "record"), new HashSet<>(diagram.nodes().stream().map(n -> n.id()).toList()));
        assertEquals(1, diagram.edges().size());
        assertEquals("reader", diagram.edges().getFirst().sourceId());
        assertEquals("record", diagram.edges().getFirst().targetId());
    }

    private static RequirementElementView element(AnalysisResult result, String id) {
        return result.getArchitectureView().getIncludedElements().stream().filter(e -> e.getNodeCode().equals(id)).findFirst().orElseThrow();
    }

    private static Edge edge(Necessity necessity, Direction direction) {
        var contribution = new Contribution(SOURCE, "read existing evidence", ORIGINAL, "reader role");
        return new Edge(contribution, TARGET, "CONSUMES", direction, new Decision("record", Outcome.VERIFIED,
                "existing evidence", ORIGINAL, necessity, necessity == Necessity.OPTIONAL ? "if requested" : "",
                necessity == Necessity.ALTERNATIVE ? "record choice" : "", "Reader needs record", ""));
    }

    private static AnalysisResult analyze(List<Edge> edges, int limit, boolean explicitZero) throws Exception {
        AnalysisResult scored = new AnalysisResult();
        Map<String,Integer> raw = new LinkedHashMap<>(Map.of("family", 40, "reader", 80));
        if (explicitZero) raw.put("record", 0);
        scored.setRawScores(raw);
        scored.setScoreSemanticsContext(Map.of("reader", new AnalysisScoreSemantics.NodeContext("family", "PRODUCT"),
                "family", new AnalysisScoreSemantics.NodeContext(null, "CATEGORY"), "record", new AnalysisScoreSemantics.NodeContext("IP", "CATEGORY")));
        scored.setStatus("SUCCESS");
        var report = new RelationSearchReport(1, "a".repeat(64), "test", List.of(),
                new Result(edges, List.of(), List.of(), 3, 3, 0), 4, 10, 0, List.of(), "");
        var llm = mock(LlmService.class);
        when(llm.analyzeWithBudget(ORIGINAL)).thenReturn(scored);
        var search = mock(RequirementRelationSearchService.class);
        when(search.isEnabled()).thenReturn(true);
        when(search.search(eq(ORIGINAL), anyMap())).thenReturn(report);
        var facade = new RequirementArchitectureViewService(new ArchitectureViewPipeline(null, new ArchitecturePipelineInvariantValidator()));
        var contexts = mock(WorkspaceViewContextReadPort.class);
        when(contexts.resolveWorkspaceBranch("tester")).thenReturn("draft");
        var useCase = new AnalyzeRequirementUseCase(llm, mock(AiPromptBudgetPolicy.class), facade, mock(AnalysisRelationGenerator.class),
                null, contexts, () -> DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        var field = AnalyzeRequirementUseCase.class.getDeclaredField("requirementRelationSearch");
        field.setAccessible(true); field.set(useCase, search);
        return useCase.analyze(new AnalyzeRequirementCommand(ORIGINAL, true, limit, null, "tester",
                new WorkspaceContext("tester", "test-workspace", "draft"))).analysisResult();
    }
}
