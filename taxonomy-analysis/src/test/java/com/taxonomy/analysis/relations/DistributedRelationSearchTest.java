package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.taxonomy.analysis.relations.RequirementRelationSearchContract.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.assertj.core.api.Assertions.*;

class DistributedRelationSearchTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void persistedPreparationAndReverseShardCompletionKeepSequentialEvidenceAndPrompts() {
        var catalogue = twoTargets();
        List<String> sequentialPrompts = new ArrayList<>();
        var sequential = search(catalogue, record(sequentialPrompts)).search(WRITE, Map.of("process", 90), OPTIONS);
        List<String> distributedPrompts = new ArrayList<>();
        var coordinator = search(catalogue, record(distributedPrompts));
        var plan = coordinator.prepare("prepared-result", WRITE, Map.of("process", 90),
                List.of("persisted-BP-result"), OPTIONS, true);
        plan = JSON.readValue(JSON.writeValueAsString(plan), RelationSearchDistribution.Plan.class);
        var results = new ArrayList<RelationSearchDistribution.WorkResult>();
        int widestWave = 0;
        while (true) {
            var wave = RelationSearchDistribution.ready(plan, results);
            if (wave.isEmpty()) break;
            widestWave = Math.max(widestWave, wave.size());
            var reversed = new ArrayList<>(wave); Collections.reverse(reversed);
            for (var work : reversed) {
                var worker = search(targetOnly(catalogue, work.targetRoot().code()), record(distributedPrompts));
                var result = worker.evaluate(WRITE, plan, work);
                results.add(JSON.readValue(JSON.writeValueAsString(result), RelationSearchDistribution.WorkResult.class));
            }
        }
        var report = RelationSearchDistribution.combine(plan, results);
        assertThat(widestWave).isGreaterThan(1);
        assertEquivalent(sequential, report);
        assertThat(distributedPrompts).containsExactlyInAnyOrderElementsOf(sequentialPrompts);
        assertThat(distributedPrompts.stream().filter(p -> p.contains("Extract ONLY")).count()).isEqualTo(1);
        var reversed = new ArrayList<>(results); Collections.reverse(reversed);
        assertThat(RelationSearchDistribution.combine(plan, reversed)).isEqualTo(report);
    }

    @Test
    void saturatedGlobalBudgetKeepsSequentialPrefixAndDoesNotMultiplyPerShard() {
        for (int calls = 0; calls < 12; calls++) {
            var options = new RequirementRelationSearch.Options(new Limits(calls, 8, 10, 128), 16);
            var prompts = new ArrayList<String>();
            var sequential = search(twoTargets(), record(prompts)).search(WRITE, Map.of("process", 90), options);
            var actual = new ArrayList<String>();
            var engine = search(twoTargets(), record(actual));
            var plan = engine.prepare("plan-" + calls, WRITE, Map.of("process", 90), List.of("root-result"), options, true);
            var results = execute(engine, plan, WRITE);
            var report = RelationSearchDistribution.combine(plan, results);
            assertThat(report.totalCalls()).as("global budget %s", calls).isEqualTo(sequential.totalCalls()).isLessThanOrEqualTo(calls);
            assertThat(actual).containsExactlyElementsOf(prompts);
            assertThat(report.result().edges()).containsExactlyElementsOf(sequential.result().edges());
            assertThat(report.result().calls()).as("attempt count at budget %s", calls).isEqualTo(sequential.result().calls());
            assertThat(report.result().unfinished()).as("unfinished at budget %s", calls).isEqualTo(sequential.result().unfinished());
            assertThat(report.tasks()).as("task states at budget %s", calls).isEqualTo(sequential.tasks());
            assertThat(report.progress().totalSearches()).isEqualTo(sequential.progress().totalSearches());
            if (calls < 5) assertThat(report.isSearchExhausted()).isFalse();
        }
    }

    @Test
    void partialWaveCannotReleaseUnusedCreditsBeforeOtherReservationsFinish() {
        var engine = search(twoTargets(), RequirementRelationSearchContract::answer);
        var plan = engine.prepare("plan", WRITE, Map.of("process", 90), List.of("root-result"), OPTIONS, true);
        var wave = RelationSearchDistribution.ready(plan, List.of());
        assertThat(wave.size()).isGreaterThan(1);
        var last = wave.getLast();
        var result = engine.evaluate(WRITE, plan, last);
        assertThat(RelationSearchDistribution.ready(plan, List.of(result))).containsExactlyElementsOf(wave.subList(0, wave.size() - 1));
        assertThat(RelationSearchDistribution.combine(plan, List.of(result)).isSearchExhausted()).isFalse();
        assertThat(RelationSearchDistribution.combine(plan, List.of(result)).result().unfinished())
                .anyMatch(u -> u.reason().equals("PENDING"));
    }

    @Test
    void fullyFundedTargetShardsCanExecuteAtTheSameTimeWithoutRepeatingExtraction() throws Exception {
        var catalogue = twoTargets();
        var plan = search(catalogue, RequirementRelationSearchContract::answer)
                .prepare("parallel", WRITE, Map.of("process", 90), List.of("root-result"), OPTIONS, true);
        var wave = RelationSearchDistribution.ready(plan, List.of());
        var left = wave.getFirst();
        var right = wave.stream().filter(w -> !w.targetRoot().equals(left.targetRoot())).findFirst().orElseThrow();
        var entered = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        Function<String, String> delayed = prompt -> {
            assertThat(prompt).doesNotContain("Extract ONLY");
            int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            entered.countDown();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).as("both independent target workers entered").isTrue();
                return answer(prompt);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            finally { active.decrementAndGet(); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> search(targetOnly(catalogue, left.targetRoot().code()), delayed).evaluate(WRITE, plan, left));
            var second = executor.submit(() -> search(targetOnly(catalogue, right.targetRoot().code()), delayed).evaluate(WRITE, plan, right));
            assertThat(first.get(10, TimeUnit.SECONDS).stopReason()).isEmpty();
            assertThat(second.get(10, TimeUnit.SECONDS).stopReason()).isEmpty();
            assertThat(peak).hasValue(2);
        }
    }

    @Test
    void preparationKeepsMixedRootSourceBatchAndContributionRoundRobinPrompts() {
        var application = new Node("application", "UA", "Evidence viewer", "Fixture application", false);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals(application.id()) ? application : catalogue().find(id); }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node n) { return catalogue().children(n); }
        };
        var scores = Map.of("process", 50, "application", 90);
        var sequentialPrompts = new ArrayList<String>();
        var expected = search(catalogue, record(sequentialPrompts)).search(WRITE, scores, OPTIONS);
        var distributedPrompts = new ArrayList<String>();
        var engine = search(catalogue, record(distributedPrompts));
        var plan = engine.prepare("mixed", WRITE, scores, List.of("UA-result", "BP-result"), OPTIONS, true);
        assertThat(plan.sources()).extracting(s -> s.node().id()).containsExactly("application", "process");
        var actual = RelationSearchDistribution.combine(plan, execute(engine, plan, WRITE));
        assertEquivalent(expected, actual);
        assertThat(distributedPrompts).containsExactlyElementsOf(sequentialPrompts);
        assertThat(plan.sourceCalls()).isEqualTo(1);
    }

    @Test
    void sourceAndWorkLimitsRemainExplicitAndBounded() {
        var many = new LinkedHashMap<String, Node>();
        var scores = new LinkedHashMap<String, Integer>();
        for (int n = 0; n < 40; n++) { String id = "process-" + n;
            many.put(id, new Node(id, "BP", id, "fixture process", false)); scores.put(id, 1); }
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return many.get(id); }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node node) { return node.equals(ROOT) ? List.of(TARGET) : List.of(); }
        };
        var prompts = new ArrayList<String>();
        var engine = search(catalogue, record(prompts));
        var opts = new RequirementRelationSearch.Options(new Limits(100, 8, 10, 2), 3);
        var plan = engine.prepare("bounded", READ, scores, List.of("root-result"), opts, true);
        var results = execute(engine, plan, READ);
        var report = RelationSearchDistribution.combine(plan, results);
        assertThat(plan.sources()).hasSize(3);
        assertThat(report.progress().totalSources()).isEqualTo(40);
        assertThat(report.progress().totalSearches()).isEqualTo(40);
        assertThat(results).hasSizeLessThanOrEqualTo(2);
        assertThat(results.stream().mapToInt(RelationSearchDistribution.WorkResult::calls).sum()).isLessThanOrEqualTo(2);
        assertThat(report.warnings()).anyMatch(w -> w.startsWith("SOURCE_LIMIT"));
        assertThat(report.result().unfinished()).anyMatch(u -> u.reason().equals("WORK_LIMIT"));
        assertThat(report.isSearchExhausted()).isFalse();
    }

    @Test
    void missingAndUnresolvedSourcesNeverBecomeNegativeCompletedFindings() {
        AtomicInteger calls = new AtomicInteger();
        var engine = search(catalogue(), p -> { calls.incrementAndGet(); return "{\"selections\":[{\"nodeId\":\"process\",\"outcome\":\"UNRESOLVED\",\"contributions\":[],\"rationale\":\"Scope unclear\",\"question\":\"Which evidence?\"}]}"; });
        var plan = engine.prepare("partial", READ, Map.of("process", 20, "missing", 10), List.of("root-result"), OPTIONS, true);
        var report = RelationSearchDistribution.combine(plan, List.of());
        assertThat(calls).hasValue(1);
        assertThat(RelationSearchDistribution.ready(plan, List.of())).isEmpty();
        assertThat(report.warnings()).anyMatch(w -> w.startsWith("MISSING_SOURCE"))
                .anyMatch(w -> w.startsWith("SOURCE_UNRESOLVED"));
        assertThat(report.result().edges()).isEmpty();
        assertThat(report.progress().unresolvedSearches()).isEqualTo(1);
        assertThat(report.isSearchExhausted()).isFalse();
    }

    @Test
    void noRelationsUsesNoCatalogueOrProviderAndSelectedTargetsStayWithinSubset() {
        var absent = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { throw new AssertionError("no source read"); }
            public List<Node> roots() { throw new AssertionError("no target read"); }
            public List<Node> children(Node n) { throw new AssertionError("no children read"); }
        };
        var skipped = search(absent, p -> { throw new AssertionError("no provider"); })
                .prepare("disabled", READ, Map.of("process", 90), List.of("root-result"), OPTIONS, false);
        assertThat(RelationSearchDistribution.ready(skipped, List.of())).isEmpty();
        assertThat(RelationSearchDistribution.combine(skipped, List.of()).totalCalls()).isZero();
        var plan = session(RequirementRelationSearchContract::answer)
                .prepare("subset", READ, Map.of("process", 90), List.of("root-result"), OPTIONS, true);
        assertThat(plan.items()).allMatch(i -> i.targetRoot().code().equals("IP"));
    }

    @Test
    void workerRejectsMissingTargetOrForeignRequirementAndDoesNotLoadSourceCatalogue() {
        var coordinator = session(RequirementRelationSearchContract::answer);
        var plan = coordinator.prepare("plan", READ, Map.of("process", 90), List.of("root-result"), OPTIONS, true);
        var work = RelationSearchDistribution.ready(plan, List.of()).getFirst();
        var wrong = search(targetOnly(twoTargets(), "CR"), p -> { throw new AssertionError("no remote call"); });
        assertThatThrownBy(() -> wrong.evaluate(READ, plan, work)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.evaluate(WRITE, plan, work)).isInstanceOf(IllegalArgumentException.class);
        var valid = search(targetOnly(catalogue(), "IP"), RequirementRelationSearchContract::answer);
        assertThat(valid.evaluate(READ, plan, work).calls()).isPositive();
    }

    @Test
    void preparationReplayRetainsSourceCohortAndOriginalBudgetCharge() {
        var later = new Node("later", "BP", "Later process", "Fixture process", false);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals(later.id()) ? later : catalogue().find(id); }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node n) { return catalogue().children(n); }
        };
        var calls = new AtomicInteger();
        var engine = search(catalogue, prompt -> { calls.incrementAndGet(); return answer(prompt); });
        var journal = new Journal();
        var options = new RequirementRelationSearch.Options(new Limits(5, 8, 10, 128), 1);
        try (var ignored = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            var first = engine.prepare("replayed", READ, Map.of("process", 20, "later", 10), List.of("root-result"), options, true);
            var replay = engine.prepare("replayed", READ, Map.of("process", 20, "later", 10), List.of("root-result"), options, true);
            assertThat(replay.sources()).isEqualTo(first.sources()).hasSize(1);
            assertThat(replay.sourceCalls()).isEqualTo(first.sourceCalls()).isEqualTo(1);
            assertThat(replay.warnings()).isEqualTo(first.warnings());
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void workReplayCannotReleasePreviouslySpentOperationCredits() {
        var calls = new AtomicInteger();
        var engine = search(catalogue(), prompt -> { calls.incrementAndGet(); return answer(prompt); });
        var plan = engine.prepare("replay-work", READ, Map.of("process", 90), List.of("root-result"), OPTIONS, true);
        var work = RelationSearchDistribution.ready(plan, List.of()).getFirst();
        var journal = new Journal();
        try (var ignored = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            var first = engine.evaluate(READ, plan, work);
            int raw = calls.get();
            var replay = engine.evaluate(READ, plan, work);
            assertThat(replay.calls()).isEqualTo(first.calls()).isPositive();
            assertThat(replay.result().edges()).isEqualTo(first.result().edges());
            assertThat(calls).hasValue(raw);
            assertThat(RelationSearchDistribution.ready(plan, List.of(replay)))
                    .isEqualTo(RelationSearchDistribution.ready(plan, List.of(first)));
        }
    }

    private static final class Journal implements com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Store {
        private final Map<String, com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint> saved = new HashMap<>();
        public com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint prepare(
                com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Question question) {
            var previous = saved.get(question.key());
            return previous != null && previous.state().equals("SUCCESS") ? previous
                    : new com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint(question, "ATTEMPT", null);
        }
        public void finish(com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Question question,
                String state, com.taxonomy.dto.LlmCallDetail detail) {
            saved.put(question.key(), new com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Checkpoint(question, state, detail));
        }
        public void checkActive() { }
    }

    private static List<RelationSearchDistribution.WorkResult> execute(RequirementRelationSearch search,
            RelationSearchDistribution.Plan plan, String original) {
        var results = new ArrayList<RelationSearchDistribution.WorkResult>();
        while (true) { var work = RelationSearchDistribution.ready(plan, results); if (work.isEmpty()) return results;
            for (var item : work) results.add(search.evaluate(original, plan, item)); }
    }
    private static void assertEquivalent(RelationSearchReport expected, RelationSearchReport actual) {
        assertThat(actual.sources()).isEqualTo(expected.sources());
        assertThat(actual.result().edges()).isEqualTo(expected.result().edges());
        assertThat(actual.result().trace()).isEqualTo(expected.result().trace());
        assertThat(actual.totalCalls()).isEqualTo(expected.totalCalls());
        assertThat(actual.tasks()).isEqualTo(expected.tasks());
        assertThat(actual.isSearchExhausted()).isEqualTo(expected.isSearchExhausted());
    }
    private static Function<String, String> record(List<String> prompts) {
        return prompt -> { prompts.add(prompt); return answer(prompt); };
    }
    private static RequirementRelationSearch search(RequirementRelationSearch.InputCatalogue catalogue, Function<String, String> complete) {
        return new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(), complete, () -> { });
    }
    private static RequirementRelationSearch.InputCatalogue twoTargets() {
        var serviceRoot = new Node("CR", "CR", "Core services", "Fixture root", true);
        return new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return catalogue().find(id); }
            public List<Node> roots() { return List.of(ROOT, serviceRoot); }
            public List<Node> children(Node n) { return catalogue().children(n); }
        };
    }
    private static RequirementRelationSearch.InputCatalogue targetOnly(RequirementRelationSearch.InputCatalogue full, String root) {
        return new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { throw new AssertionError("target worker must not read sources"); }
            public List<Node> roots() { return full.roots().stream().filter(n -> n.root().equals(root)).toList(); }
            public List<Node> children(Node n) { assertThat(n.root()).isEqualTo(root); return full.children(n); }
        };
    }
}
