package com.taxonomy.analysis.service;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.analysis.dag.AnalysisTaskOutcome;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.RequirementReference;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisCompleted;
import com.taxonomy.analysis.dag.inprocess.InProcessAnalysisOperation;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScope;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Root scoring through the in-process task graph keeps the provider call
 * sequence, prompts and result semantics of the monolithic run, and broker/task
 * metadata never reaches a prompt.
 */
class AnalysisTaskDagEquivalenceTest {

    private static final String REQUIREMENT = "Hospital staff need integrated voice communication";
    private static final String OPERATION_ID = "0f6b2c1e-8d7a-4b3c-9e2f-1a2b3c4d5e6f";

    private static TaxonomyNode node(String code, String parent) {
        var n = new TaxonomyNode();
        n.setCode(code); n.setNameEn(code); n.setParentCode(parent); n.setTaxonomyRoot(code.substring(0, 2));
        return n;
    }

    private static final class Catalogue extends TaxonomyService {
        Catalogue() { super(null, null, null); }
        @Override public List<TaxonomyNode> getRootNodes() {
            // Catalogue order differs from analysis priority (BP, CP, CR, ...).
            return new ArrayList<>(List.of(node("CR", null), node("BP", null), node("CP", null)));
        }
        @Override public List<TaxonomyNode> getChildrenOf(String parent) {
            return switch (parent) {
                case "BP" -> List.of(node("BP-1", "BP"));
                case "CR" -> List.of(node("CR-1", "CR"));
                default -> List.of();
            };
        }
        @Override public Map<String, String> getAssessmentContexts(List<TaxonomyNode> nodes) { return Map.of(); }
        @Override public List<com.taxonomy.dto.TaxonomyNodeDto> getFullTree() { return List.of(); }
    }

    private static final class Provider extends LlmProviderConfig {
        Provider() { super(null); }
        @Override public LlmProvider getActiveProvider() { return LlmProvider.GEMINI; }
        @Override public String getActiveProviderName() { return "GEMINI"; }
        @Override public String getApiKey(LlmProvider requested) { return "test-key"; }
    }

    /** Deterministic replies selected by the most specific code in the prompt. */
    private static final class Gateway implements LlmGateway {
        static final Map<String, Integer> SCORES = Map.of("BP", 40, "CP", 0, "CR", 30, "BP-1", 40, "CR-1", 30);
        final List<String> prompts = new ArrayList<>();
        final String rateLimitedCode;
        Gateway(String rateLimitedCode) { this.rateLimitedCode = rateLimitedCode; }
        @Override public String sendHttpRequest(String prompt, String key) {
            prompts.add(prompt);
            String code = SCORES.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                    .filter(c -> prompt.contains("\"" + c + "\"") || prompt.contains(c + ":")
                            || prompt.contains(c + " ") || prompt.contains(c + "\n"))
                    .findFirst().orElseThrow();
            if (code.equals(rateLimitedCode)) throw new LlmRateLimitException("quota");
            return "{\"" + code + "\":{\"score\":" + SCORES.get(code) + ",\"reason\":\"evidence " + code + "\"}}";
        }
        @Override public String extractResponseText(String body) { return body; }
        @Override public String providerName() { return "fake remote"; }
    }

    private static LlmService service(Gateway gateway) {
        var config = new Provider();
        var registry = new LlmGatewayRegistry(config, new RestTemplate(), new ObjectMapper(), null, null, null) {
            @Override public LlmGateway getGateway(LlmProvider selected) { return gateway; }
        };
        var prompts = new PromptTemplateService();
        prompts.loadDefaults();
        return new LlmService(config, registry, new ObjectMapper(), new Catalogue(), prompts, null, null);
    }

    private static AnalysisOperationContext operation(String requirement) {
        return new AnalysisOperationContext(OPERATION_ID,
                new AnalysisSourceAuthority("repo-1", "ws-1", "draft", "abc123"),
                RequirementReference.adHoc(requirement), null);
    }

    @Test
    void graphExecutionMatchesStandaloneScoringAndKeepsTaskMetadataOutOfPrompts() {
        var standaloneGateway = new Gateway(null);
        AnalysisResult standalone = service(standaloneGateway).analyzeWithBudget(REQUIREMENT);

        var operationGateway = new Gateway(null);
        AnalysisResult viaOperation;
        try (var op = InProcessAnalysisOperation.open(operation(REQUIREMENT), null)) {
            viaOperation = service(operationGateway).analyzeWithBudget(REQUIREMENT);

            var graph = op.graph().orElseThrow();
            assertThat(graph.subtaxonomyRoots()).extracting(r -> r.code()).containsExactly("BP", "CP", "CR");
            assertThat(graph.includesRelations()).isTrue();
            assertThat(op.completions().values()).hasSize(3)
                    .allSatisfy(c -> assertThat(c.taskId().operationId()).isEqualTo(OPERATION_ID));
            var rootCompletions = op.completions().values().stream()
                    .map(SubtaxonomyAnalysisCompleted.class::cast)
                    .collect(java.util.stream.Collectors.toMap(c -> c.root().code(), c -> c));
            assertThat(rootCompletions.get("BP").rootScore()).isEqualTo(40);
            assertThat(rootCompletions.get("CP").rootScore()).isZero();
            assertThat(rootCompletions.get("CR").scoredNodes()).isEqualTo(2);
            assertThat(rootCompletions.values()).extracting(SubtaxonomyAnalysisCompleted::outcome)
                    .containsOnly(AnalysisTaskOutcome.COMPLETED);
            // Relation work belongs to the coordinating use case, not to root scoring.
            assertThat(op.dispatched()).noneMatch(id -> id.value().contains(":relation:"));
        }

        // One root assessment per root plus one batch per scored parent: 3 + BP-1 + CR-1.
        assertThat(standaloneGateway.prompts).hasSize(5);
        assertThat(operationGateway.prompts).isEqualTo(standaloneGateway.prompts);
        assertThat(viaOperation.getScores()).isEqualTo(standalone.getScores());
        assertThat(viaOperation.getRawScores()).isEqualTo(standalone.getRawScores());
        assertThat(viaOperation.getReasons()).isEqualTo(standalone.getReasons());
        assertThat(viaOperation.getStatus()).isEqualTo(standalone.getStatus()).isEqualTo("SUCCESS");
        assertThat(standalone.getRawScores()).containsEntry("BP", 40).containsEntry("CP", 0)
                .containsEntry("CR", 30).containsEntry("BP-1", 40).containsEntry("CR-1", 30);

        for (String prompt : operationGateway.prompts) {
            assertThat(prompt).doesNotContain(OPERATION_ID).doesNotContain(":subtaxonomy:")
                    .doesNotContain("subtaxonomy").doesNotContain("repo-1").doesNotContain("abc123")
                    .doesNotContain(RequirementReference.sha256(REQUIREMENT));
        }
    }

    @Test
    void rateLimitSkipsRemainingRootTasksWithTheExistingPartialMessage() {
        var gateway = new Gateway("CP");
        AnalysisResult result;
        try (var op = InProcessAnalysisOperation.open(operation(REQUIREMENT), null)) {
            result = service(gateway).analyzeWithBudget(REQUIREMENT);
            assertThat(op.completions().values()).extracting(c -> ((SubtaxonomyAnalysisCompleted) c).outcome())
                    .containsExactly(AnalysisTaskOutcome.COMPLETED, AnalysisTaskOutcome.SKIPPED,
                            AnalysisTaskOutcome.SKIPPED);
        }
        assertThat(result.getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getErrorMessage()).isEqualTo("Rate limit reached after processing: BP. Skipped: CP, CR.");
        // CR is never sent to the provider after the rate limit.
        assertThat(gateway.prompts).noneMatch(p -> p.contains("CR"));
    }

    @Test
    void selectedTaxonomyOnlyScopePlansExactlySelectedRootsWithoutRelations() {
        var gateway = new Gateway(null);
        try (var op = InProcessAnalysisOperation.open(operation(REQUIREMENT), null)) {
            service(gateway).analyzeWithBudget(REQUIREMENT,
                    new AnalysisScope(Set.of("CR"), AnalysisMode.TAXONOMIES_ONLY));
            var graph = op.graph().orElseThrow();
            assertThat(graph.subtaxonomyRoots()).extracting(r -> r.code()).containsExactly("CR");
            assertThat(graph.tasksOf(AnalysisTaskType.RELATION_ANALYSIS)).isEmpty();
        }
        assertThat(gateway.prompts).hasSize(2);
    }

    @Test
    void scoringNeverJoinsAnOperationForAnotherRequirementVersion() {
        var gateway = new Gateway(null);
        try (var op = InProcessAnalysisOperation.open(operation("a different requirement"), null)) {
            service(gateway).analyzeWithBudget(REQUIREMENT);
            assertThat(op.canPlan()).isTrue();
            assertThat(op.completions()).isEmpty();
            assertThat(InProcessAnalysisOperation.current()).containsSame(op);
        }
        assertThat(gateway.prompts).hasSize(5);
    }
}
