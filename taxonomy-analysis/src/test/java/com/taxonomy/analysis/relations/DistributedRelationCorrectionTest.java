package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.taxonomy.analysis.relations.RequirementRelationSearchContract.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Branch grants must cover the same adaptive and corrective work as local execution. */
class DistributedRelationCorrectionTest {
    @Test
    void sourceLimitAdmitsThePartialFinalBatchLocallyAndDuringPreparation() {
        List<Node> sources = new ArrayList<>();
        for (int n = 0; n < 40; n++) sources.add(new Node("process-" + n, "BP", "Process " + n, "Catalogue function", false));
        assertSourceLimit(sources, 32);
    }

    @Test
    void taxonomyBoundaryCannotWasteTheRemainingSourceAllowance() {
        assertSourceLimit(List.of(
                new Node("process-first", "BP", "Process one", "Catalogue function", false),
                new Node("process-second", "BP", "Process two", "Catalogue function", false),
                new Node("application-first", "UA", "Application one", "Catalogue function", false),
                new Node("application-second", "UA", "Application two", "Catalogue function", false)), 3);
    }

    @Test
    void correctionCanFinishVerificationWhenTheGlobalBudgetHasRoom() {
        var localPrompts = new ArrayList<String>();
        var expected = search(catalogue(), failFirstNavigation(localPrompts))
                .search(READ, Map.of(SOURCE.id(), 90d), OPTIONS);
        assertThat(expected.result().edges()).hasSize(1);
        assertThat(localPrompts).anyMatch(prompt -> prompt.contains("validationErrors"));

        var distributedPrompts = new ArrayList<String>();
        var actual = distributed(catalogue(), failFirstNavigation(distributedPrompts));

        assertThat(actual.report().result().edges()).isEqualTo(expected.result().edges());
        assertThat(actual.report().isSearchExhausted()).isTrue();
        assertThat(actual.report().totalCalls()).isEqualTo(expected.totalCalls());
        assertThat(distributedPrompts).containsExactlyInAnyOrderElementsOf(localPrompts);
        assertWithinGrants(actual);
    }

    @Test
    void adaptiveWholeDescriptionBatchesCanFinishWithValidResponses() {
        List<Node> children = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            children.add(new Node("evidence-" + n, "IP", "Evidence record " + n,
                    "Complete catalogue description " + "x".repeat(7_000), false));
        }
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return SOURCE.id().equals(id) ? SOURCE : null; }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node node) { return node.equals(ROOT) ? children : List.of(); }
        };
        var localPrompts = new ArrayList<String>();
        var expected = search(catalogue, record(localPrompts))
                .search(READ, Map.of(SOURCE.id(), 90d), OPTIONS);
        assertThat(expected.result().edges()).hasSize(4);
        assertThat(expected.isSearchExhausted()).isTrue();
        long childBatches = localPrompts.stream().filter(prompt -> {
            var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
            return "NAVIGATE".equals(input.path("phase").asString())
                    && input.path("candidates").get(0).get("id").asString().startsWith("evidence-");
        }).count();
        assertThat(childBatches).isEqualTo(4);

        var distributedPrompts = new ArrayList<String>();
        var actual = distributed(catalogue, record(distributedPrompts));

        assertThat(actual.report().result().edges()).isEqualTo(expected.result().edges());
        assertThat(actual.report().isSearchExhausted()).isTrue();
        assertThat(actual.report().totalCalls()).isEqualTo(expected.totalCalls());
        assertThat(distributedPrompts).containsExactlyInAnyOrderElementsOf(localPrompts);
        assertWithinGrants(actual);
    }

    private record Execution(RelationSearchReport report, List<RelationSearchDistribution.WorkResult> results) { }

    private static Execution distributed(RequirementRelationSearch.InputCatalogue catalogue,
                                         Function<String, String> completion) {
        var search = search(catalogue, completion);
        var plan = search.prepare("corrective-grants", READ, Map.of(SOURCE.id(), 90d),
                List.of("persisted-source"), OPTIONS, true);
        var results = new ArrayList<RelationSearchDistribution.WorkResult>();
        while (true) {
            var wave = RelationSearchDistribution.ready(plan, results);
            if (wave.isEmpty()) break;
            for (var work : wave) results.add(search.evaluate(READ, plan, work));
        }
        return new Execution(RelationSearchDistribution.combine(plan, results), List.copyOf(results));
    }

    private static void assertSourceLimit(List<Node> sources, int maxSources) {
        var nodes = new LinkedHashMap<String, Node>();
        var scores = new LinkedHashMap<String, Double>();
        for (int n = 0; n < sources.size(); n++) {
            Node node = sources.get(n); nodes.put(node.id(), node); scores.put(node.id(), (double) sources.size() - n);
        }
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return nodes.get(id); }
            public List<Node> roots() { return List.of(); }
            public List<Node> children(Node node) { return List.of(); }
        };
        Function<String, String> reject = prompt -> {
            var input = JSON.readTree(prompt.substring(prompt.indexOf("INPUT\n") + 6));
            List<Map<String, Object>> answers = new ArrayList<>();
            for (var node : input.get("nodes")) answers.add(Map.of("nodeId", node.get("id").asString(),
                    "outcome", "REJECT", "contributions", List.of(), "rationale", "This function is not requested", "question", ""));
            return JSON.writeValueAsString(Map.of("selections", answers));
        };
        var options = new RequirementRelationSearch.Options(new Limits(100, 8, 3, 128), maxSources);
        var local = search(catalogue, reject).search(READ, scores, options);
        var plan = search(catalogue, reject).prepare("source-boundary", READ, scores,
                List.of("persisted-sources"), options, true);

        assertThat(local.sources()).hasSize(maxSources);
        assertThat(plan.sources()).isEqualTo(local.sources());
        assertThat(local.sources()).extracting(source -> source.node().id())
                .containsExactlyElementsOf(sources.subList(0, maxSources).stream().map(Node::id).toList());
        assertThat(plan.sourceCalls()).isEqualTo(local.totalCalls());
        assertThat(plan.items()).isEmpty();
        assertThat(local.warnings()).anyMatch(warning -> warning.startsWith("SOURCE_LIMIT"));
    }

    private static void assertWithinGrants(Execution execution) {
        assertThat(execution.report().totalCalls()).isLessThanOrEqualTo(OPTIONS.limits().maxCalls());
        assertThat(execution.results()).allSatisfy(result -> {
            assertThat(result.calls()).isLessThanOrEqualTo(result.work().maxCalls());
            assertThat(result.calls()).isLessThanOrEqualTo(result.work().maxWorkItems());
        });
    }

    private static Function<String, String> failFirstNavigation(List<String> prompts) {
        var failed = new AtomicBoolean();
        return prompt -> {
            prompts.add(prompt);
            if (prompt.contains("NAVIGATE: find") && failed.compareAndSet(false, true)) return "invalid JSON";
            return answer(prompt);
        };
    }

    private static Function<String, String> record(List<String> prompts) {
        return prompt -> {
            prompts.add(prompt);
            assertThat(RelationSearchProtocol.requestCharacters(prompt)).isLessThanOrEqualTo(OPTIONS.maxPromptCharacters());
            return answer(prompt);
        };
    }

    private static RequirementRelationSearch search(RequirementRelationSearch.InputCatalogue catalogue,
                                                   Function<String, String> completion) {
        return new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), completion, () -> { });
    }
}
