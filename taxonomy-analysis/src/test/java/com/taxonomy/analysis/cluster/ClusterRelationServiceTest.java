package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RelationSearchModel;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ClusterRelationServiceTest {
    @Test void preparationAndEvaluationPersistOnlyThroughTheCompletionCallback() {
        var store = new Store();
        var signals = new ClusterAnalysisSignals();
        var preparation = task(store.context, null);
        var plan = emptyPlan(store.context.operationId());
        var service = new ClusterRelationService(store, (input, task, cancelled) -> {
            assertThat(signals.workerCount()).isEqualTo(1);
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ClusterRelationComputation.Preparation(plan);
        }, signals);
        var prepared = service.prepare(preparation);
        assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.COMPLETED);
        assertThat(store.savedPlan).isNull();
        assertThat(signals.workerCount()).isZero();
        prepared.persistEffect().run();
        assertThat(store.savedPlan).isSameAs(plan);
        var work = new RelationSearchDistribution.Work(plan.preparationId(), 0, TaxonomyShardRoot.of("IP"), 2, 2);
        var result = new RelationSearchDistribution.WorkResult(work,
                new RelationSearchModel.Result(List.of(), List.of(new RelationSearchModel.Unfinished("process", "CONSUMES",
                        RelationSearchModel.Direction.OUTGOING, List.of("IP"), "CALL_BUDGET", "")), List.of(), 2, 1, 0), 1, "");
        var evaluation = new ClusterRelationService(store, (input, task, cancelled) ->
                new ClusterRelationComputation.Evaluation(result), signals).prepare(task(store.context, work));
        assertThat(evaluation.completion().outcome()).isEqualTo(AnalysisTaskOutcome.PARTIAL);
        assertThat(store.savedResult).isNull();
        evaluation.persistEffect().run();
        assertThat(store.savedResult).isSameAs(result);
    }

    @Test void knownFailedComputationSettlesWithASanitizedExecutionFailureRatherThanEmptyEvidence() {
        var store = new Store();
        var service = new ClusterRelationService(store, (input, task, cancelled) -> {
            throw new IllegalStateException("provider secret must never enter completion");
        }, new ClusterAnalysisSignals());
        var prepared = service.prepare(task(store.context, null));
        assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.FAILED);
        assertThat(store.failure).isNull();
        prepared.persistEffect().run();
        assertThat(store.failure).isEqualTo("RELATION_PREPARATION_FAILED");
        assertThat(store.savedPlan).isNull();
        assertThat(store.savedResult).isNull();
    }

    @Test void cancellationBeforeStartAndDuringComputationNeverPublishesSuccessfulEvidence() {
        var store = new Store();
        var calls = new AtomicInteger();
        var signals = new ClusterAnalysisSignals();
        var messages = new AnalysisMessageFactory(store.context, Clock.systemUTC());
        var service = new ClusterRelationService(store, (input, task, cancelled) -> {
            calls.incrementAndGet();
            signals.cancellation(messages.cancellation("CANCELLED"));
            assertThat(cancelled.getAsBoolean()).isTrue();
            return new ClusterRelationComputation.Preparation(emptyPlan(store.context.operationId()));
        }, signals);
        var task = task(store.context, null);
        var late = service.prepare(task);
        assertThat(late.completion().outcome()).isEqualTo(AnalysisTaskOutcome.STOPPED);
        assertThat(late.completion().stopReason()).isEqualTo("CANCELLED");
        late.persistEffect().run();
        assertThat(store.savedPlan).isNull();
        assertThat(store.failure).isEqualTo("CANCELLED");
        store.executable = false;
        var queued = service.prepare(task);
        assertThat(queued.completion().outcome()).isEqualTo(AnalysisTaskOutcome.STOPPED);
        assertThat(calls).hasValue(1);
        assertThat(signals.workerCount()).isZero();
    }

    @Test void retainedCooperativeStopInTypedPlanBecomesStoppedCompletionAndRetainsPlan() {
        var store = new Store();
        var plan = emptyPlan(store.context.operationId());
        var stopped = new RelationSearchDistribution.Plan(plan.schemaVersion(), plan.preparationId(), plan.originalSha256(),
                plan.sourceResultIds(), plan.options(), plan.sourceNodes(), plan.targetRoots(), plan.sources(), plan.items(),
                plan.sourceCalls(), plan.durationMillis(), plan.warnings(), "TIME_LIMIT: completed evidence retained", true);
        var prepared = new ClusterRelationService(store, (input, task, cancelled) ->
                new ClusterRelationComputation.Preparation(stopped), new ClusterAnalysisSignals()).prepare(task(store.context, null));
        assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.STOPPED);
        assertThat(prepared.completion().stopReason()).isEqualTo("TIME_LIMIT");
        prepared.persistEffect().run();
        assertThat(store.savedPlan).isSameAs(stopped);
    }

    @Test void typedPhaseMismatchFailsBeforeAnIncompatibleDurableWrite() {
        var store = new Store();
        var work = new RelationSearchDistribution.Work(store.context.operationId() + ":relations", 0,
                TaxonomyShardRoot.of("IP"), 1, 1);
        var service = new ClusterRelationService(store, (input, task, cancelled) ->
                new ClusterRelationComputation.Preparation(emptyPlan(store.context.operationId())), new ClusterAnalysisSignals());
        var prepared = service.prepare(task(store.context, work));
        assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.FAILED);
        prepared.persistEffect().run();
        assertThat(store.failure).isEqualTo("RELATION_EVALUATION_FAILED");
        assertThat(store.savedPlan).isNull();
    }

    static RelationSearchDistribution.Plan emptyPlan(String operation) {
        return new RequirementRelationSearch(new RequirementRelationSearch.InputCatalogue() {
            public RelationSearchModel.Node find(String id) { throw new AssertionError(); }
            public List<RelationSearchModel.Node> roots() { throw new AssertionError(); }
            public List<RelationSearchModel.Node> children(RelationSearchModel.Node n) { throw new AssertionError(); }
        }, new com.taxonomy.relations.service.RelationCompatibilityMatrix(), p -> { throw new AssertionError(); }, () -> { })
                .prepare(operation + ":relations", "requirement", Map.of(), List.of(),
                        new RequirementRelationSearch.Options(new RelationSearchModel.Limits(24, 8, 10, 512), 32), false);
    }

    static RelationAnalysisTask task(AnalysisOperationContext context, RelationSearchDistribution.Work work) {
        var roots = work == null ? TaxonomyShardRoot.DEFAULT_ROOTS.stream().sorted().toList() : List.of(work.targetRoot());
        var id = work == null ? AnalysisTaskId.relation(context.operationId(), roots)
                : AnalysisTaskId.relationWork(context.operationId(), work.targetRoot(), work.ordinal());
        var envelope = new AnalysisEnvelope(work == null ? 1 : 2, AnalysisMessageType.RELATION_ANALYSIS_TASK,
                context.operationId(), id, AnalysisTaskType.RELATION_ANALYSIS, context.authority(), context.requirement(),
                roots, 1, null, context.correlationId(), Clock.systemUTC().instant(), null);
        return new RelationAnalysisTask(envelope, roots, List.of(AnalysisTaskId.subtaxonomy(context.operationId(), TaxonomyShardRoot.of("BP"))));
    }

    static class Store implements ClusterRelationService.Store {
        final AnalysisOperationContext context = ClusterAnalysisStoreTest.context("relations");
        AnalyzeRequirementCommand command = ClusterAnalysisStoreTest.command("requirement");
        boolean executable = true;
        String input;
        RelationSearchDistribution.Plan plan, savedPlan;
        RelationSearchDistribution.WorkResult savedResult;
        List<RelationHypothesisDto> savedScoreOnly;
        String failure;
        AnalysisResult roots = new AnalysisResult(Map.of("process", 50), List.of());
        final Map<String, String> snapshots = new LinkedHashMap<>();
        final List<String> reads = new ArrayList<>();
        int rootReads;
        public ClusterAnalysisStore.Input start(AnalysisTaskMessage task) {
            return new ClusterAnalysisStore.Input(context, command, executable, input);
        }
        public String shard(AnalysisOperationContext context, TaxonomyShardRoot root) {
            reads.add(root.code()); return Objects.requireNonNull(snapshots.get(root.code()), "missing snapshot");
        }
        public AnalysisResult rootResults(AnalysisOperationContext context) { rootReads++; return roots; }
        public RelationSearchDistribution.Plan relationPlan(AnalysisOperationContext context) { return plan; }
        public void persistRelationPlan(RelationAnalysisTask task, RelationSearchDistribution.Plan value) { savedPlan = value; }
        public void persistRelationResult(RelationAnalysisTask task, RelationSearchDistribution.WorkResult value) { savedResult = value; }
        public void persistScoreOnlyRelations(RelationAnalysisTask task, List<RelationHypothesisDto> value) { savedScoreOnly = value; }
        public void persistRelationFailure(RelationAnalysisTask task, String reason) { failure = reason; }
    }
}
