package com.taxonomy.architecture.service;

import com.taxonomy.dto.AnalysisCopilotResult;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.DetectedPattern;
import com.taxonomy.dto.RecommendedElement;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.AnalysisCoverage.Descendants;
import com.taxonomy.dto.AnalysisCoverage.NodeAssessment;
import com.taxonomy.dto.AnalysisCoverage.State;
import com.taxonomy.dto.RelationSearchModel.Contribution;
import com.taxonomy.dto.RelationSearchModel.Decision;
import com.taxonomy.dto.RelationSearchModel.Direction;
import com.taxonomy.dto.RelationSearchModel.Edge;
import com.taxonomy.dto.RelationSearchModel.Necessity;
import com.taxonomy.dto.RelationSearchModel.Node;
import com.taxonomy.dto.RelationSearchModel.Outcome;
import com.taxonomy.dto.RelationSearchModel.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Deterministic retained-evidence fixtures; none of these identifiers are live catalogue entries. */
class AnalysisCopilotServiceTest {
    private final AnalysisCopilotService service = new AnalysisCopilotService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void connectedVerifiedChainSurvivesIncomingSearchDirectionAndSerialization() {
        AnalysisResult analysis = analysis();
        analysis.setRelationSearchReport(
            report(
                List.of(
                    edge("cap", "CP", "service", "CR", "REALIZES", Outcome.VERIFIED, Direction.OUTGOING),
                    edge("process", "BP", "service", "CR", "SUPPORTS", Outcome.VERIFIED, Direction.INCOMING),
                    edge("process", "BP", "information", "IP", "CONSUMES", Outcome.VERIFIED, Direction.OUTGOING)
                )
            )
        );
        AnalysisCopilotResult result = service.analyze(analysis, "Retained requirement");
        assertEquals("COMPLETED", result.state());
        assertEquals(1, result.schemaVersion());
        assertEquals(List.of("Full Stack"), result.patterns().getMatchedPatterns().stream().map(DetectedPattern::getPatternName).toList());
        assertEquals(100.0, result.patterns().getMatchedPatterns().getFirst().getCompleteness());
        assertTrue(
            result.gaps().getMissingRelations().stream().noneMatch(g -> "cap".equals(g.getSourceNodeCode()) && "REALIZES".equals(g.getExpectedRelationType()))
        );
        analysis.setCopilot(result);
        JsonNode wire = mapper.valueToTree(analysis);
        AnalysisResult restored = mapper.treeToValue(wire, AnalysisResult.class);
        assertEquals(mapper.valueToTree(result).toString(), mapper.valueToTree(restored.getCopilot()).toString());
        assertNull(mapper.readValue("{}", AnalysisResult.class).getCopilot());
    }

    @Test
    void disconnectedOrUnverifiedRelationsNeverCompletePatterns() {
        AnalysisResult analysis = analysis();
        analysis.getTree().add(node("otherService", "CR"));
        analysis.setRelationSearchReport(
            report(
                List.of(
                    edge("cap", "CP", "service", "CR", "REALIZES", Outcome.VERIFIED, Direction.OUTGOING),
                    edge("otherService", "CR", "process", "BP", "SUPPORTS", Outcome.VERIFIED, Direction.OUTGOING),
                    edge("process", "BP", "information", "IP", "CONSUMES", Outcome.VERIFIED, Direction.OUTGOING),
                    edge("service", "CR", "process", "BP", "SUPPORTS", Outcome.MATCH, Direction.OUTGOING)
                )
            )
        );
        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");
        assertTrue(result.patterns().getMatchedPatterns().isEmpty());
        DetectedPattern incomplete = result.patterns().getIncompletePatterns().getFirst();
        assertEquals(1, incomplete.getPresentSteps().size());
        assertTrue(incomplete.getMissingSteps().stream().allMatch(s -> s.contains("not evidenced")));
    }

    @Test
    void conditionalAlternativeEvidenceRemainsVisibleAndDoesNotProveAnUnconditionalChain() {
        AnalysisResult analysis = analysis();
        Edge conditional = new Edge(
            new Contribution(new Node("cap", "CP", "Capability", "", false), "Retained contribution", "Retained quote", "During failover"),
            new Node("service", "CR", "Service", "", false),
            "REALIZES",
            Direction.OUTGOING,
            new Decision(
                "service",
                Outcome.VERIFIED,
                "Retained contribution",
                "Retained quote",
                Necessity.ALTERNATIVE,
                "If the service is available",
                "deployment-choice",
                "Retained verification",
                ""
            )
        );
        analysis.setRelationSearchReport(
            report(
                List.of(
                    conditional,
                    edge("service", "CR", "process", "BP", "SUPPORTS", Outcome.VERIFIED, Direction.OUTGOING),
                    edge("process", "BP", "information", "IP", "CONSUMES", Outcome.VERIFIED, Direction.OUTGOING)
                )
            )
        );
        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");
        assertTrue(result.patterns().getMatchedPatterns().isEmpty());
        DetectedPattern pattern = result.patterns().getIncompletePatterns().getFirst();
        String steps = String.join(" ", pattern.getPresentSteps());
        assertTrue(steps.contains("ALTERNATIVE"));
        assertTrue(steps.contains("During failover"));
        assertTrue(steps.contains("If the service is available"));
        assertTrue(steps.contains("deployment-choice"));
        assertTrue(pattern.getCompleteness() < 100.0);
        assertTrue(pattern.getMissingSteps().stream().anyMatch(s -> s.contains("applicability")));
        assertTrue(
            result.gaps()
                .getMissingRelations()
                .stream()
                .anyMatch(g -> "cap".equals(g.getSourceNodeCode()) && "REALIZES".equals(g.getExpectedRelationType()) && g.getDescription().contains("conditional"))
        );
    }

    @Test
    void verifiedClaimsWithMismatchedRetainedIdentitiesCannotProveRelations() {
        AnalysisResult analysis = analysis();
        Edge validShape = edge("cap", "CP", "service", "CR", "REALIZES", Outcome.VERIFIED, Direction.OUTGOING);
        Edge wrongDecisionTarget = new Edge(validShape.contribution(), validShape.target(),
                validShape.type(), validShape.direction(),
                new Decision("unoffered", Outcome.VERIFIED, "Retained contribution", "Retained quote",
                        Necessity.REQUIRED, "", "", "Retained verification", ""));
        analysis.setRelationSearchReport(report(List.of(
                wrongDecisionTarget,
                edge("cap", "CP", "service", "BP", "REALIZES", Outcome.VERIFIED, Direction.OUTGOING),
                edge("cap", "CP", "unknown", "CR", "REALIZES", Outcome.VERIFIED, Direction.OUTGOING),
                edge("service", "CR", "process", "BP", "SUPPORTS", Outcome.VERIFIED, Direction.OUTGOING),
                edge("process", "BP", "information", "IP", "CONSUMES", Outcome.VERIFIED, Direction.OUTGOING))));

        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");

        assertEquals("PARTIAL", result.state());
        assertTrue(result.patterns().getMatchedPatterns().isEmpty());
        assertTrue(result.gaps().getMissingRelations().stream().anyMatch(g ->
                "cap".equals(g.getSourceNodeCode()) && "REALIZES".equals(g.getExpectedRelationType())
                        && g.getDescription().contains("not evidenced")));
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("identities")));
    }

    @Test
    void aConnectedUnconditionalPathWinsOverAnOptionalAlternativeInStableOrder() {
        AnalysisResult analysis = analysis();
        analysis.getTree().add(node("otherService", "CR"));
        Edge optional = new Edge(
                new Contribution(new Node("cap", "CP", "Capability", "", false),
                        "Retained contribution", "Retained quote", ""),
                new Node("otherService", "CR", "Other service", "", false), "REALIZES", Direction.OUTGOING,
                new Decision("otherService", Outcome.VERIFIED, "Retained contribution", "Retained quote",
                        Necessity.OPTIONAL, "", "", "Retained verification", ""));
        List<Edge> edges = List.of(optional,
                edge("cap", "CP", "service", "CR", "REALIZES", Outcome.VERIFIED, Direction.OUTGOING),
                edge("otherService", "CR", "process", "BP", "SUPPORTS", Outcome.VERIFIED, Direction.OUTGOING),
                edge("service", "CR", "process", "BP", "SUPPORTS", Outcome.VERIFIED, Direction.OUTGOING),
                edge("process", "BP", "information", "IP", "CONSUMES", Outcome.VERIFIED, Direction.OUTGOING));
        analysis.setRelationSearchReport(report(edges));

        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");

        assertEquals(1, result.patterns().getMatchedPatterns().size());
        DetectedPattern pattern = result.patterns().getMatchedPatterns().getFirst();
        assertEquals(100.0, pattern.getCompleteness());
        assertEquals("cap → REALIZES → service", pattern.getPresentSteps().getFirst());
        assertTrue(pattern.getMissingSteps().isEmpty());
        analysis.setRelationSearchReport(report(edges.reversed()));
        assertEquals(mapper.valueToTree(result), mapper.valueToTree(service.analyze(analysis, "Requirement")));
    }

    @Test
    void exhaustedPrunedSearchDoesNotProveMissingRelations() {
        AnalysisResult analysis = analysis();
        analysis.setRelationSearchReport(report(List.of()));
        assertTrue(analysis.getRelationSearchReport().isSearchExhausted());
        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");
        assertFalse(result.gaps().getMissingRelations().isEmpty());
        assertTrue(
            result.gaps()
                .getMissingRelations()
                .stream()
                .allMatch(g -> g.getDescription().contains("not evidenced") && g.getDescription().contains("needs checking"))
        );
        assertTrue(result.notes().stream().anyMatch(s -> s.contains("completeness")));
    }

    @Test
    void recommendationsUseRetainedScoresReasonsAndNamesInStableOrder() {
        AnalysisResult analysis = analysis();
        analysis.setScores(Map.of("unretained", 100.0));
        analysis.setRawScores(Map.of("unretained", 100.0, "service", 90.0, "cap", 90.0, "process", 60.0));
        analysis.setReasons(Map.of("cap", "Recorded capability reason"));
        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");
        assertEquals("PARTIAL", result.state());
        ArchitectureRecommendation recommendation = result.recommendation();
        assertEquals(List.of("cap", "service"), recommendation.getConfirmedElements().stream().map(RecommendedElement::getNodeCode).toList());
        assertEquals("Retained cap", recommendation.getConfirmedElements().getFirst().getTitle());
        assertTrue(recommendation.getConfirmedElements().getFirst().getReasoning().contains("Recorded capability reason"));
        assertEquals(90.0, recommendation.getConfirmedElements().getFirst().getScore());
        assertEquals(80.0, recommendation.getConfidence());
        assertEquals(List.of("process"), recommendation.getProposedElements().stream().map(RecommendedElement::getNodeCode).toList());
        assertTrue(recommendation.getNotes().stream().anyMatch(s -> s.contains("human approval")));
        assertEquals(mapper.valueToTree(result).toString(), mapper.valueToTree(service.analyze(analysis, "Requirement")).toString());
    }

    @Test
    void partialAndRestrictedScopeSkipCompletenessReports() {
        AnalysisResult analysis = analysis();
        analysis.setStatus("PARTIAL");
        AnalysisCopilotResult partial = service.analyze(analysis, "Requirement");
        assertEquals("SKIPPED_PARTIAL", partial.state());
        assertTrue(partial.recommendation().getConfirmedElements().isEmpty());
        assertTrue(partial.gaps().getMissingRelations().isEmpty());
        analysis.setStatus("SUCCESS");
        analysis.setAnalysisScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL));
        assertEquals("SKIPPED_SCOPE", service.analyze(analysis, "Requirement").state());
        analysis.setAnalysisScope(new AnalysisScope(Set.of(), AnalysisMode.TAXONOMIES_ONLY));
        assertEquals("SKIPPED_SCOPE", service.analyze(analysis, "Requirement").state());
    }

    @Test
    void openCoverageAndMissingSourceNeverBecomeCompletedReports() {
        AnalysisResult analysis = analysis();
        analysis.setAnalysisCoverage(
            new AnalysisCoverage(Map.of("service", new NodeAssessment(State.UNKNOWN, null, null, Descendants.UNASSESSED, "FAILED: retained failure")), 0, 1, 1)
        );
        assertEquals("SKIPPED_PARTIAL", service.analyze(analysis, "Requirement").state());
        assertEquals("SKIPPED_PARTIAL", service.analyze(null, "Requirement").state());
    }

    @Test
    void productSuitabilityIsNotPromotedToAbsoluteRelevance() {
        TaxonomyNodeDto family = node("family", "IP");
        TaxonomyNodeDto product = node("product", "IP");
        product.setAnalysisRole("PRODUCT");
        product.setParentCode("family");
        family.getChildren().add(product);
        AnalysisResult analysis = new AnalysisResult(Map.of("family", 30.0, "product", 100.0), List.of(family));
        analysis.setStatus("SUCCESS");
        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");
        assertTrue(result.recommendation().getConfirmedElements().isEmpty());
        assertTrue(result.recommendation().getProposedElements().isEmpty());
        assertEquals(0, result.gaps().getTotalAnchors());
    }

    @Test
    void reportGrowthIsBoundedAndTruncationIsExplicit() {
        ArrayList<TaxonomyNodeDto> nodes = new ArrayList<>();
        TreeMap<String, Double> scores = new TreeMap<>();

        for (int i = 0; i < 1000; i++) {
            String code = "fixture-" + i;
            nodes.add(node(code, "CP"));
            scores.put(code, 90.0);
        }

        AnalysisResult analysis = new AnalysisResult(scores, nodes);
        analysis.setStatus("SUCCESS");
        analysis.setRelationSearchReport(report(List.of()));
        AnalysisCopilotResult result = service.analyze(analysis, "Requirement");
        assertEquals("PARTIAL", result.state());
        assertTrue(result.recommendation().getConfirmedElements().size() <= 200);
        assertTrue(result.gaps().getMissingRelations().size() <= 500);
        assertTrue(result.notes().stream().anyMatch(s -> s.contains("limit")));
    }

    private static AnalysisResult analysis() {
        AnalysisResult result = new AnalysisResult(
            Map.of("cap", 90.0, "service", 80.0, "process", 60.0),
            new ArrayList<>(List.of(node("cap", "CP"), node("service", "CR"), node("process", "BP"), node("information", "IP")))
        );
        result.setStatus("SUCCESS");
        return result;
    }

    private static TaxonomyNodeDto node(String code, String root) {
        TaxonomyNodeDto result = new TaxonomyNodeDto();
        result.setCode(code);
        result.setTaxonomyRoot(root);
        result.setNameEn("Retained " + code);
        result.setLevel(1);
        return result;
    }

    private static RelationSearchReport report(List<Edge> edges) {
        return new RelationSearchReport(1, "fixture", "fixture-pruning", List.of(), new Result(edges, List.of(), List.of(), 0, 0, 0L), 0, 0, 0L, List.of(), "");
    }

    private static Edge edge(String source, String sourceRoot, String target, String targetRoot, String type, Outcome outcome, Direction direction) {
        return new Edge(
            new Contribution(new Node(source, sourceRoot, source, "", false), "Retained contribution", "Retained quote", ""),
            new Node(target, targetRoot, target, "", false),
            type,
            direction,
            new Decision(target, outcome, "Retained contribution", "Retained quote", Necessity.REQUIRED, "", "", "Retained verification", "")
        );
    }
}
