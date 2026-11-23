package com.taxonomy.analysis.relations;

import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.dsl.model.CatalogueDefinition;
import com.taxonomy.dto.RelationSearchProgress;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void smallBudgetChecksTheConcreteCounterpartBeforeAnUnscoredTaxonomy() {
        var catalogue = twoTargets();
        var options = new RequirementRelationSearch.Options(new Limits(5, 8, 10, 128), 16);
        var scores = Map.of(SOURCE.id(), 90d, TARGET.id(), 70d);
        var engine = search(catalogue, RequirementRelationSearchContract::answer);

        var sequential = engine.search(READ, scores, options);
        var plan = engine.prepare("focused-search", READ, scores, List.of("scored-bp", "scored-ip"), options, true);
        var distributed = RelationSearchDistribution.combine(plan, execute(engine, plan, READ));

        assertThat(sequential.result().edges()).extracting(Edge::targetId).containsExactly(TARGET.id());
        assertThat(distributed.result().edges()).isEqualTo(sequential.result().edges());
        assertThat(sequential.totalCalls()).isEqualTo(5);
        assertThat(distributed.totalCalls()).isEqualTo(5);
        assertThat(distributed.result().unfinished()).anyMatch(unfinished -> "CR".equals(unfinished.targetRoot()));
        assertThat(distributed.isSearchExhausted()).isFalse();
    }

    @Test
    void acceptedProfileRestrictsRelationCandidatesBeforeWorkerDispatch() {
        var model = new com.taxonomy.dto.AnalysisModelBinding(com.taxonomy.dto.AnalysisModelBinding.State.RESOLVED,
                "repo", "workspace", "draft", "a".repeat(40), 1L, "GIT_CHECKPOINT", "b".repeat(64), "catalogue",
                "1", Set.of("BP", "IP"), com.taxonomy.dto.AnalysisScope.full(), "brief", Map.of(), List.of(),
                Set.of("PRODUCES"), null);
        var engine = search(twoTargets(), record(new ArrayList<>()));
        try (var profile = com.taxonomy.relations.service.AdmittedRelationRules.open(model)) {
            var filtered = engine.prepare("filtered", WRITE, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
            assertThat(filtered.items()).extracting(RelationSearchDistribution.Item::type)
                    .contains("CONSUMES").doesNotContain("PRODUCES");
        }
        var independent = engine.prepare("independent", WRITE, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        assertThat(independent.items()).extracting(RelationSearchDistribution.Item::type).contains("CONSUMES", "PRODUCES");
    }

    @Test
    void savedMuseumPlanReplaysItsDeclaredDirectionAndLeavesTheDeepBranchOpen() {
        var definition = new CatalogueDefinition("museum-1", true,
                List.of(new CatalogueDefinition.Root("Museum Collections", "MC", "MuseumCollection")),
                List.of(new CatalogueDefinition.Direction("BP", "CONSUMES", "MC")));
        var branch = new ArrayList<Node>();
        branch.add(new Node("MC", "MC", "Museum collections", "Collection hierarchy", true));
        for (int depth = 1; depth <= 10; depth++)
            branch.add(new Node("MC-" + depth, "MC", "Collection " + depth, "Catalogue entry", depth < 10));
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals(SOURCE.id()) ? SOURCE : null; }
            public List<Node> roots() { return List.of(branch.getFirst()); }
            public List<Node> children(Node node) {
                int index = branch.indexOf(node);
                return index < 0 || index + 1 == branch.size() ? List.of() : List.of(branch.get(index + 1));
            }
        };
        var options = new RequirementRelationSearch.Options(new Limits(12, 2, 10, 128), 16);
        var coordinator = new RequirementRelationSearch(catalogue, new RelationCompatibilityMatrix(definition),
                RequirementRelationSearchContract::answer, () -> {});
        var prepared = coordinator.prepare("museum-plan", READ, Map.of(SOURCE.id(), 90d), List.of("scored-process"), options, true);
        var saved = JSON.readValue(JSON.writeValueAsString(prepared), RelationSearchDistribution.Plan.class);
        assertThat(saved.schemaVersion()).isEqualTo(2);
        assertThat(saved.items()).anyMatch(item -> item.type().equals("CONSUMES") && item.targetRoot().code().equals("MC"));

        var worker = search(catalogue, RequirementRelationSearchContract::answer);
        var report = RelationSearchDistribution.combine(saved, execute(worker, saved, READ));
        assertThat(report.isSearchExhausted()).isFalse();
        assertThat(report.totalCalls()).isLessThanOrEqualTo(options.limits().maxCalls());
        assertThat(report.result().unfinished()).anyMatch(open -> open.reason().equals("DEPTH_LIMIT")
                && open.targetRoot().equals("MC") && !open.targets().isEmpty());
        assertThat(report.result().edges()).isEmpty();
    }

    @Test
    void persistedPreparationAndReverseShardCompletionKeepSequentialEvidenceAndPrompts() {
        var catalogue = twoTargets();
        List<String> sequentialPrompts = new ArrayList<>();
        var sequential = search(catalogue, record(sequentialPrompts)).search(WRITE, Map.of("process", 90d), OPTIONS);
        List<String> distributedPrompts = new ArrayList<>();
        var coordinator = search(catalogue, record(distributedPrompts));
        var plan = coordinator.prepare("prepared-result", WRITE, Map.of("process", 90d),
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
        reversed.add(reversed.getFirst());
        RelationSearchDistribution.validateResults(plan, reversed);
        assertThat(RelationSearchDistribution.combine(plan, reversed)).isEqualTo(report);
    }

    @Test
    void distributedHistoryRejectsConflictingGrantsAndForeignEvidence() {
        var engine = search(twoTargets(), RequirementRelationSearchContract::answer);
        var plan = engine.prepare("validation-plan", WRITE, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        var wave = RelationSearchDistribution.ready(plan, List.of());
        var work = wave.getFirst();
        var valid = engine.evaluate(WRITE, plan, work);
        var result = valid.result();
        var intent = RelationSearchDistribution.intent(plan, plan.items().get(work.ordinal()));
        var direction = intent.direction();
        var otherRoot = wave.stream().map(RelationSearchDistribution.Work::targetRoot)
                .filter(root -> !root.equals(work.targetRoot())).findFirst().orElseThrow();
        var otherWork = wave.stream().filter(grant -> grant.targetRoot().equals(otherRoot)).findFirst().orElseThrow();
        var foreignTarget = new Node("other-target", otherRoot.code(), "Foreign target", "", false);
        var foreignEdge = new Edge(intent.contribution(), foreignTarget, intent.type(), direction,
                new Decision(foreignTarget.id(), Outcome.VERIFIED, "relation", WRITE, Necessity.REQUIRED, "", "", "", ""));
        var foreignTrace = new Trace("foreign-source", intent.type(), direction, Phase.NAVIGATE,
                foreignTarget.id(), Outcome.REJECT, "");
        var unfinished = new Unfinished("foreign-source", intent.type(), direction,
                List.of(foreignTarget.id()), "UNRESOLVED", "");
        var invalid = Map.of(
                "foreign preparation", List.of(new RelationSearchDistribution.WorkResult(
                        new RelationSearchDistribution.Work("other-plan", work.ordinal(), work.targetRoot(),
                                work.maxCalls(), work.maxWorkItems()), result, valid.calls(), valid.stopReason())),
                "changed target root", List.of(new RelationSearchDistribution.WorkResult(
                        new RelationSearchDistribution.Work(work.preparationId(), work.ordinal(), otherRoot,
                                work.maxCalls(), work.maxWorkItems()), result, valid.calls(), valid.stopReason())),
                "changed ordinal", List.of(new RelationSearchDistribution.WorkResult(
                        new RelationSearchDistribution.Work(work.preparationId(), otherWork.ordinal(), work.targetRoot(),
                                work.maxCalls(), work.maxWorkItems()), result, valid.calls(), valid.stopReason())),
                "spent more than the issued budget", List.of(new RelationSearchDistribution.WorkResult(
                        new RelationSearchDistribution.Work(work.preparationId(), work.ordinal(), work.targetRoot(),
                                work.maxCalls() + 1, work.maxWorkItems() + 1), result, work.maxCalls() + 1, "")),
                "conflicting duplicate", List.of(valid, new RelationSearchDistribution.WorkResult(
                        work, result, valid.calls(), "RELATION_EVALUATION_FAILED")),
                "foreign edge", List.of(new RelationSearchDistribution.WorkResult(work,
                        new Result(List.of(foreignEdge), List.of(), List.of(), 1, 1, 0), 1, "")),
                "foreign trace", List.of(new RelationSearchDistribution.WorkResult(work,
                        new Result(List.of(), List.of(), List.of(foreignTrace), 1, 1, 0), 1, "")),
                "foreign unfinished relation", List.of(new RelationSearchDistribution.WorkResult(work,
                        new Result(List.of(), List.of(unfinished), List.of(), 1, 1, 0), 1, "")));
        invalid.forEach((reason, history) -> {
            assertThatIllegalArgumentException().as(reason)
                    .isThrownBy(() -> RelationSearchDistribution.combine(plan, history));
            assertThatIllegalArgumentException().as(reason)
                    .isThrownBy(() -> RelationSearchDistribution.validateResults(plan, history));
        });
    }

    @Test
    void distributedHistoryCanBeValidatedWithoutRequestingAReport() {
        var engine = search(twoTargets(), RequirementRelationSearchContract::answer);
        var plan = engine.prepare("validation-plan", WRITE, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        var results = execute(engine, plan, WRITE);
        RelationSearchDistribution.validateResults(plan, results);
    }

    @Test
    void exhaustedFrontierRejectsAnUnfinishedRelationWithoutAReason() {
        var plan = completionValidationPlan(1, null);
        var work = RelationSearchDistribution.ready(plan, List.of()).getFirst();
        var unfinished = new Unfinished(SOURCE.id(), "CONSUMES", Direction.OUTGOING, List.of(ROOT.id()), null, "");
        var results = List.of(new RelationSearchDistribution.WorkResult(work,
                new Result(List.of(), List.of(unfinished), List.of(), 1, 1, 0), 1, ""));

        assertThat(RelationSearchDistribution.ready(plan, results)).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> RelationSearchDistribution.combine(plan, results));
        assertThatNullPointerException().isThrownBy(() -> RelationSearchDistribution.validateResults(plan, results));
    }

    @Test
    void unfinishedRelationWithoutAReasonRemainsReadableWhileWaveIsPending() {
        var plan = completionValidationPlan(2, null);
        var wave = RelationSearchDistribution.ready(plan, List.of());
        var unfinished = new Unfinished(SOURCE.id(), "CONSUMES", Direction.OUTGOING, List.of(ROOT.id()), null, "");
        var results = List.of(new RelationSearchDistribution.WorkResult(wave.getFirst(),
                new Result(List.of(), List.of(unfinished), List.of(), 1, 1, 0), 1, ""));

        assertThat(RelationSearchDistribution.ready(plan, results)).containsExactly(wave.getLast());
        assertThat(RelationSearchDistribution.combine(plan, results).result().unfinished()).contains(unfinished);
        RelationSearchDistribution.validateResults(plan, results);
    }

    @Test
    void earlierBudgetEvidenceShortCircuitsALaterMissingReason() {
        var plan = completionValidationPlan(1, null);
        var work = RelationSearchDistribution.ready(plan, List.of()).getFirst();
        var exhausted = new Unfinished(SOURCE.id(), "CONSUMES", Direction.OUTGOING, List.of(ROOT.id()), "CALL_BUDGET", "");
        var unfinished = new Unfinished(SOURCE.id(), "CONSUMES", Direction.OUTGOING, List.of(ROOT.id()), null, "");
        var results = List.of(new RelationSearchDistribution.WorkResult(work,
                new Result(List.of(), List.of(exhausted, unfinished), List.of(), 1, 1, 0), 1, ""));

        assertThat(RelationSearchDistribution.combine(plan, results).result().unfinished()).contains(exhausted, unfinished);
        RelationSearchDistribution.validateResults(plan, results);
    }

    @Test
    void completionCannotOverflowTheSavedCumulativeCallCount() {
        var plan = completionValidationPlan(1, Integer.MAX_VALUE);
        var work = RelationSearchDistribution.ready(plan, List.of()).getFirst();
        var results = List.of(new RelationSearchDistribution.WorkResult(work,
                new Result(List.of(), List.of(), List.of(), 1, 1, 0), 1, ""));

        assertThat(RelationSearchDistribution.ready(plan, results)).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> RelationSearchDistribution.combine(plan, results));
        assertThatIllegalArgumentException().isThrownBy(() -> RelationSearchDistribution.validateResults(plan, results));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void cumulativeCallCountUsesActualChargesAndCanReachTheRepresentableLimit(int charged) {
        var plan = completionValidationPlan(1, Integer.MAX_VALUE - charged);
        var work = RelationSearchDistribution.ready(plan, List.of()).getFirst();
        var result = new RelationSearchDistribution.WorkResult(work,
                new Result(List.of(), List.of(), List.of(), 0, 0, 0), charged, "");
        var results = List.of(result, result);

        assertThat(RelationSearchDistribution.combine(plan, results).budget().spentCalls()).isEqualTo(Integer.MAX_VALUE);
        RelationSearchDistribution.validateResults(plan, results);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sourceAssessmentMissingAProgressFieldCannotBePresented(boolean missingNode) {
        var plan = completionValidationPlan(1, null, missingNode ? null : SOURCE, missingNode ? "" : null);

        assertThat(RelationSearchDistribution.ready(plan, List.of())).hasSize(1);
        assertThatNullPointerException().isThrownBy(() -> RelationSearchDistribution.combine(plan, List.of()));
        assertThatNullPointerException().isThrownBy(() -> RelationSearchDistribution.validateResults(plan, List.of()));
    }

    private static RelationSearchDistribution.Plan completionValidationPlan(int maxCalls, Integer before) {
        return completionValidationPlan(maxCalls, before, SOURCE, "");
    }

    private static RelationSearchDistribution.Plan completionValidationPlan(int maxCalls, Integer before,
            Node assessmentNode, String question) {
        var budget = before == null ? null
                : new RequirementRelationSearch.BudgetPolicy(maxCalls, maxCalls, before, false, 16, 128, 16);
        var options = new RequirementRelationSearch.Options(new Limits(maxCalls, 8, 10, 128), 16, 16_000, budget);
        var root = com.taxonomy.analysis.dag.TaxonomyShardRoot.of(ROOT.root());
        var contribution = new Contribution(SOURCE, "review evidence", WRITE, "");
        return new RelationSearchDistribution.Plan(1, "report-validation-plan", "a".repeat(64), List.of("root-result"),
                options, List.of(SOURCE), List.of(ROOT),
                List.of(new SourceAssessment(assessmentNode, List.of(contribution), "", question)),
                List.of(new RelationSearchDistribution.Item(0, 0, 0, "CONSUMES", Direction.OUTGOING, root, 1),
                        new RelationSearchDistribution.Item(1, 0, 0, "PRODUCES", Direction.OUTGOING, root, 1)),
                0, 0, List.of(), "", true);
    }

    @Test
    void saturatedGlobalBudgetKeepsSequentialPrefixAndDoesNotMultiplyPerShard() {
        for (int calls = 0; calls < 12; calls++) {
            var options = new RequirementRelationSearch.Options(new Limits(calls, 8, 10, 128), 16);
            var prompts = new ArrayList<String>();
            var sequential = search(twoTargets(), record(prompts)).search(WRITE, Map.of("process", 90d), options);
            var actual = new ArrayList<String>();
            var engine = search(twoTargets(), record(actual));
            var plan = engine.prepare("plan-" + calls, WRITE, Map.of("process", 90d), List.of("root-result"), options, true);
            var results = execute(engine, plan, WRITE);
            RelationSearchDistribution.validateResults(plan, results);
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
        var plan = engine.prepare("plan", WRITE, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        var wave = RelationSearchDistribution.ready(plan, List.of());
        assertThat(wave.size()).isGreaterThan(1);
        var last = wave.getLast();
        var result = engine.evaluate(WRITE, plan, last);
        RelationSearchDistribution.validateResults(plan, List.of(result));
        assertThat(RelationSearchDistribution.ready(plan, List.of(result))).containsExactlyElementsOf(wave.subList(0, wave.size() - 1));
        assertThat(RelationSearchDistribution.combine(plan, List.of(result)).isSearchExhausted()).isFalse();
        assertThat(RelationSearchDistribution.combine(plan, List.of(result)).result().unfinished())
                .anyMatch(u -> u.reason().equals("PENDING"));
    }

    @Test
    void fullyFundedTargetShardsCanExecuteAtTheSameTimeWithoutRepeatingExtraction() throws Exception {
        var catalogue = twoTargets();
        var plan = search(catalogue, RequirementRelationSearchContract::answer)
                .prepare("parallel", WRITE, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
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
    void preparationSeparatesTaxonomySourceBatchesAndKeepsContributionRoundRobinPrompts() {
        var application = new Node("application", "UA", "Evidence viewer", "Fixture application", false);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return id.equals(application.id()) ? application : catalogue().find(id); }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node n) { return catalogue().children(n); }
        };
        var scores = Map.of("process", 50d, "application", 90d);
        var sequentialPrompts = new ArrayList<String>();
        var expected = search(catalogue, record(sequentialPrompts)).search(WRITE, scores, OPTIONS);
        var distributedPrompts = new ArrayList<String>();
        var engine = search(catalogue, record(distributedPrompts));
        var plan = engine.prepare("mixed", WRITE, scores, List.of("UA-result", "BP-result"), OPTIONS, true);
        assertThat(plan.sources()).extracting(s -> s.node().id()).containsExactly("application", "process");
        var actual = RelationSearchDistribution.combine(plan, execute(engine, plan, WRITE));
        assertEquivalent(expected, actual);
        assertThat(distributedPrompts).containsExactlyElementsOf(sequentialPrompts);
        assertThat(plan.sourceCalls()).isEqualTo(2);
    }

    @Test
    void scarceSourceCallsAdmitOneIntactBatchPerTaxonomyInBothExecutionModes() {
        var fixture = new MixedSourceCohort();
        var localCalls = new ArrayList<String>();
        var local = search(fixture, record(localCalls)).search(READ, fixture.scores, fixture.options);
        var distributedCalls = new ArrayList<String>();
        var engine = search(fixture, record(distributedCalls));
        var plan = engine.prepare("fair-cohort", READ, fixture.scores, List.of("scored-roots"), fixture.options, true);

        var expected = List.of(fixture.prompt(0), fixture.prompt(3));
        assertThat(localCalls.stream().filter(prompt -> prompt.contains("Extract ONLY")).toList()).isEqualTo(expected);
        assertThat(distributedCalls).isEqualTo(expected);
        assertThat(plan.sourceCalls()).isEqualTo(2);
        assertThat(plan.sources()).isEqualTo(local.sources());
        assertThat(plan.sourceNodes()).containsExactlyElementsOf(fixture.ranked);
        var distributed = RelationSearchDistribution.combine(plan, execute(engine, plan, READ));
        for (var report : List.of(local, distributed)) {
            assertThat(report.totalCalls()).isLessThanOrEqualTo(4);
            assertThat(report.progress().totalSources()).isEqualTo(8);
            assertThat(report.progress().assessedSources()).isEqualTo(4);
            assertThat(report.progress().sourceTaxonomies()).extracting(RelationSearchProgress.SourceTaxonomy::root)
                    .containsExactlyInAnyOrder("BP", "CI");
            assertThat(report.warnings()).anyMatch(warning -> warning.startsWith("SOURCE_LIMIT"));
            assertThat(report.isSearchExhausted()).isFalse();
        }
    }

    @Test
    void continuationReusesTheFormerBpFirstBatchesWithoutNewProviderCallsForThoseKeys() {
        var fixture = new MixedSourceCohort();
        var journal = new Journal();
        var calls = new ArrayList<String>();
        try (var session = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            // Seed the complete prompts/answers previously admitted before CI received a turn.
            var protocol = new RelationSearchProtocol(record(calls));
            protocol.contributionsRecovering(READ, fixture.batch(0));
            protocol.contributionsRecovering(READ, fixture.batch(1));
        }
        assertThat(calls).containsExactly(fixture.prompt(0), fixture.prompt(1));
        calls.clear();
        try (var session = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            var resumed = search(fixture, record(calls)).search(READ, fixture.scores, fixture.options);
            assertThat(calls.stream().filter(prompt -> prompt.contains("Extract ONLY")).toList())
                    .containsExactly(fixture.prompt(3), fixture.prompt(2));
            assertThat(resumed.sources().stream().map(SourceAssessment::node)).containsExactlyElementsOf(fixture.ranked);
            assertThat(resumed.totalCalls()).isLessThanOrEqualTo(4);
        }
    }

    @Test
    void redeliveryOfALegacyFrozenGrantRecountsTheOriginalPrefixBeforeAnyNewCall() {
        var fixture = new MixedSourceCohort();
        var storedBudget = JSON.readValue("""
                {"callsPerSlice":4,"maxTotalCalls":40,"callsBeforeExecution":0,"automatic":false,
                 "sourcesPerSlice":8,"workItemsPerSlice":128,"sourceCohortSize":8}
                """, RequirementRelationSearch.BudgetPolicy.class);
        assertThat(JSON.valueToTree(storedBudget).has("batchSchedule")).isFalse();
        assertThat(JSON.readValue(JSON.writeValueAsString(fixture.options), RequirementRelationSearch.Options.class))
                .isEqualTo(fixture.options);
        var options = new RequirementRelationSearch.Options(fixture.options.limits(), 8, 16_000, storedBudget);
        var journal = new Journal();
        var calls = new ArrayList<String>();
        try (var session = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            var protocol = new RelationSearchProtocol(record(calls));
            protocol.contributionsRecovering(READ, fixture.batch(0));
            protocol.contributionsRecovering(READ, fixture.batch(1));
            calls.clear();
            var plan = search(fixture, record(calls)).prepare("legacy-retry", READ, fixture.scores,
                    List.of("scored-roots"), options, true);
            assertThat(calls).isEmpty();
            assertThat(plan.sourceCalls()).isEqualTo(2);
            assertThat(plan.sources().stream().map(SourceAssessment::node)).containsExactlyElementsOf(fixture.ranked.subList(0, 4));
            assertThat(plan.warnings()).anyMatch(warning -> warning.startsWith("SOURCE_LIMIT"));
        }
    }

    private static final class MixedSourceCohort implements RequirementRelationSearch.InputCatalogue {
        final List<Node> ranked = new ArrayList<>();
        final Map<String, Double> scores = new HashMap<>();
        final RequirementRelationSearch.Options options = new RequirementRelationSearch.Options(new Limits(4, 8, 2, 128), 8, 16_000,
                new RequirementRelationSearch.BudgetPolicy(4, 40, 0, false, 8, 128, 8));

        MixedSourceCohort() {
            for (int i = 0; i < 8; i++) {
                String root = i < 6 ? "BP" : "CI";
                String id = root + "-fixture-" + i;
                ranked.add(new Node(id, root, id, "Fixture source", false));
                scores.put(id, 95d - i);
            }
        }

        List<Node> batch(int index) { return ranked.subList(2 * index, 2 * index + 2); }
        String prompt(int index) { return RelationSearchProtocol.sourcePrompt(READ, batch(index), Map.of()); }
        public Node find(String id) { return ranked.stream().filter(node -> node.id().equals(id)).findFirst().orElse(null); }
        public List<Node> roots() { return List.of(ROOT); }
        public List<Node> children(Node node) { return List.of(); }
    }

    @Test
    void sourceAndWorkLimitsRemainExplicitAndBounded() {
        var many = new LinkedHashMap<String, Node>();
        var scores = new LinkedHashMap<String, Double>();
        for (int n = 0; n < 40; n++) { String id = "process-" + n;
            many.put(id, new Node(id, "BP", id, "fixture process", false)); scores.put(id, 1d); }
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
        var plan = engine.prepare("partial", READ, Map.of("process", 20d, "missing", 10d), List.of("root-result"), OPTIONS, true);
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
    void successfulReplayCompletesPreviouslyUnresolvedTask() {
        var engine = search(catalogue(), RequirementRelationSearchContract::answer);
        var plan = engine.prepare("resolve-unresolved", READ, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        var replay = execute(engine, plan, READ);
        var initial = RelationSearchDistribution.combine(plan, replay);
        var item = plan.items().getFirst();
        var previous = withUnresolved(initial, plan, List.of(item));

        var continued = RelationSearchDistribution.combine(plan, replay, previous);

        assertThat(continued.tasks()).contains(new RelationSearchProgress.Task(itemSource(plan, item),
                item.targetRoot().code(), RelationSearchProgress.State.COMPLETED));
        assertThat(continued.isSearchExhausted()).isTrue();
    }

    @Test
    void replayingOneIntentDoesNotClearAnotherFailureInTheSameTask() {
        var engine = search(catalogue(), RequirementRelationSearchContract::answer);
        var plan = engine.prepare("retain-sibling-failure", READ, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        var item = plan.items().getFirst();
        var sibling = plan.items().stream().filter(candidate -> candidate.ordinal() != item.ordinal()
                && candidate.sourceIndex() == item.sourceIndex()
                && candidate.targetRoot().equals(item.targetRoot())).findFirst().orElseThrow();
        var initial = RelationSearchDistribution.combine(plan, execute(engine, plan, READ));
        var previous = withUnresolved(initial, plan, List.of(item, sibling));
        var work = RelationSearchDistribution.ready(plan, List.of()).stream()
                .filter(candidate -> candidate.ordinal() == item.ordinal()).findFirst().orElseThrow();
        var replay = engine.evaluate(READ, plan, work);

        var continued = RelationSearchDistribution.combine(plan, List.of(replay), previous);

        assertThat(continued.tasks()).contains(new RelationSearchProgress.Task(itemSource(plan, item),
                item.targetRoot().code(), RelationSearchProgress.State.UNRESOLVED));
        assertThat(continued.isSearchExhausted()).isFalse();
    }

    @Test
    void noRelationsUsesNoCatalogueOrProviderAndSelectedTargetsStayWithinSubset() {
        var absent = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { throw new AssertionError("no source read"); }
            public List<Node> roots() { throw new AssertionError("no target read"); }
            public List<Node> children(Node n) { throw new AssertionError("no children read"); }
        };
        var skipped = search(absent, p -> { throw new AssertionError("no provider"); })
                .prepare("disabled", READ, Map.of("process", 90d), List.of("root-result"), OPTIONS, false);
        assertThat(RelationSearchDistribution.ready(skipped, List.of())).isEmpty();
        assertThat(RelationSearchDistribution.combine(skipped, List.of()).totalCalls()).isZero();
        var plan = session(RequirementRelationSearchContract::answer)
                .prepare("subset", READ, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
        assertThat(plan.items()).allMatch(i -> i.targetRoot().code().equals("IP"));
    }

    @Test
    void workerRejectsMissingTargetOrForeignRequirementAndDoesNotLoadSourceCatalogue() {
        var coordinator = session(RequirementRelationSearchContract::answer);
        var plan = coordinator.prepare("plan", READ, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
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
            var first = engine.prepare("replayed", READ, Map.of("process", 20d, "later", 10d), List.of("root-result"), options, true);
            var replay = engine.prepare("replayed", READ, Map.of("process", 20d, "later", 10d), List.of("root-result"), options, true);
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
        var plan = engine.prepare("replay-work", READ, Map.of("process", 90d), List.of("root-result"), OPTIONS, true);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldTargetGrantReplaysItsPaidSplitPrefixAndNewGrantUsesPackingOrder(boolean withFrozenBudget) {
        var first = new Node("evidence-a", "IP", "First", "a".repeat(9_000), false);
        var last = new Node("evidence-z", "IP", "Last", "z".repeat(9_000), false);
        var catalogue = new RequirementRelationSearch.InputCatalogue() {
            public Node find(String id) { return SOURCE.id().equals(id) ? SOURCE : null; }
            public List<Node> roots() { return List.of(ROOT); }
            public List<Node> children(Node node) { return node.equals(ROOT) ? List.of(first, last) : List.of(); }
        };
        var budget = withFrozenBudget ? new RequirementRelationSearch.BudgetPolicy(4, 20, 0, false, 8, 128, 8) : null;
        var options = new RequirementRelationSearch.Options(new Limits(4, 8, 10, 128), 8, 16_000, budget);
        var calls = new ArrayList<String>();
        var engine = search(catalogue, record(calls));
        var fresh = engine.prepare("split-targets", READ, Map.of(SOURCE.id(), 90d), List.of("scored-BP"), options, true);
        var grant = RelationSearchDistribution.ready(fresh, List.of()).getFirst();
        assertThat(grant.maxCalls()).isEqualTo(3);
        var document = JSON.valueToTree(fresh);
        var oldOptions = (tools.jackson.databind.node.ObjectNode) document.path("options");
        oldOptions.remove("standaloneBatchSchedule");
        if (withFrozenBudget) {
            var oldBudget = (tools.jackson.databind.node.ObjectNode) oldOptions.path("budget");
            oldBudget.remove("batchSchedule");
        }
        var old = JSON.treeToValue(document, RelationSearchDistribution.Plan.class);
        var intent = RelationSearchDistribution.intent(old, old.items().get(grant.ordinal()));
        var journal = new Journal();
        try (var session = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(journal)) {
            // The old worker prepended split batches in forward order and verified the last child first.
            var protocol = new RelationSearchProtocol(record(calls));
            var root = new Query(READ, intent.contribution(), intent.type(), intent.direction(), Phase.NAVIGATE, List.of(ROOT), null);
            var child = new Query(READ, intent.contribution(), intent.type(), intent.direction(), Phase.NAVIGATE, List.of(last), null);
            assertThat(protocol.evaluateRecovering(root).getFirst().outcome()).isEqualTo(Outcome.DESCEND);
            var proposal = protocol.evaluateRecovering(child).getFirst();
            var verified = protocol.evaluateRecovering(new Query(READ, intent.contribution(), intent.type(), intent.direction(),
                    Phase.VERIFY, List.of(last), proposal)).getFirst();
            assertThat(verified.outcome()).isEqualTo(Outcome.VERIFIED);
            calls.clear();

            var replay = engine.evaluate(READ, old, grant);

            assertThat(calls).as("redelivery must spend no new provider calls").isEmpty();
            assertThat(replay.calls()).isEqualTo(3);
            assertThat(replay.result().edges()).extracting(Edge::evidence).containsExactly(verified);
            assertThat(replay.result().unfinished()).anyMatch(gap -> gap.targets().contains(first.id()));
        }

        var newPlan = JSON.readValue(JSON.writeValueAsString(fresh), RelationSearchDistribution.Plan.class);
        var result = engine.evaluate(READ, newPlan, grant);
        assertThat(result.calls()).isEqualTo(3);
        assertThat(result.result().edges()).extracting(Edge::targetId).containsExactly(first.id());
        assertThat(result.result().unfinished()).anyMatch(gap -> gap.targets().contains(last.id()));
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
    private static RelationSearchReport withUnresolved(RelationSearchReport report,
            RelationSearchDistribution.Plan plan, List<RelationSearchDistribution.Item> failures) {
        var first = failures.getFirst();
        String source = itemSource(plan, first), root = first.targetRoot().code();
        var tasks = report.tasks().stream().map(task -> task.sourceId().equals(source) && task.targetRoot().equals(root)
                ? new RelationSearchProgress.Task(source, root, RelationSearchProgress.State.UNRESOLVED) : task).toList();
        var unfinished = new ArrayList<>(report.result().unfinished());
        for (var item : failures) {
            var intent = RelationSearchDistribution.intent(plan, item);
            unfinished.add(new Unfinished(source, item.type(), item.direction(), List.of(root), "UNRESOLVED",
                    "Which evidence supports this relation?",
                    RelationSearchEngine.contributionKey(intent.contribution(), item.type(), item.direction()), root));
        }
        var result = new Result(report.result().edges(), unfinished, report.result().trace(),
                report.result().calls(), report.result().visitedBatches(), report.result().durationMillis());
        return new RelationSearchReport(report.schemaVersion(), report.originalSha256(), report.policy(), report.sources(),
                result, report.totalCalls(), report.maxCalls(), report.durationMillis(), report.warnings(),
                report.stopReason(), report.progress(), tasks, report.budget());
    }
    private static String itemSource(RelationSearchDistribution.Plan plan, RelationSearchDistribution.Item item) {
        return plan.sources().get(item.sourceIndex()).node().id();
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
