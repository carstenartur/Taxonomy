package com.taxonomy.analysis.dag;

import com.taxonomy.analysis.dag.inprocess.InProcessAnalysisOperation;
import com.taxonomy.analysis.dag.inprocess.InProcessAnalysisTaskTransport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InProcessAnalysisOperationTest {

    private static final List<TaxonomyShardRoot> ROOTS = List.of(
            TaxonomyShardRoot.of("BP"), TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("CR"));

    private static AnalysisOperationContext context(String operationId) {
        return new AnalysisOperationContext(operationId, new AnalysisSourceAuthority("repo", "ws", "draft", "c1"),
                RequirementReference.adHoc("requirement"), null);
    }

    private static final class Recorder implements AnalysisEventPublisher {
        final List<AnalysisProgressEvent> progress = new ArrayList<>();
        @Override public void progress(AnalysisProgressEvent event) { progress.add(event); }
        @Override public void cancellation(AnalysisCancellationEvent event) { }
    }

    @Test
    void dispatchesRootsInPlanOrderOnCallingThreadThenRelationsWithKnownTotals() {
        var events = new Recorder();
        Thread caller = Thread.currentThread();
        var executed = new ArrayList<String>();
        try (var operation = InProcessAnalysisOperation.open(context("op-a"), events)) {
            assertThat(InProcessAnalysisOperation.current()).containsSame(operation);
            operation.plan(ROOTS, true);
            var roots = operation.runSubtaxonomyTasks(task -> {
                assertThat(Thread.currentThread()).isSameAs(caller);
                executed.add(task.root().code());
                return operation.messages().completed(task, AnalysisTaskOutcome.COMPLETED, 10, 1, null);
            });
            assertThat(executed).containsExactly("BP", "CP", "CR");
            assertThat(roots).extracting(SubtaxonomyAnalysisCompleted::root).containsExactlyElementsOf(ROOTS);

            var relations = operation.runRelationTasks(List.of(), task -> {
                assertThat(task.prerequisiteTasks()).hasSize(3);
                return operation.messages().completed(task, AnalysisTaskOutcome.COMPLETED, 2, null);
            });
            assertThat(relations).singleElement().satisfies(r -> assertThat(r.relationCount()).isEqualTo(2));
            // Re-running does not re-dispatch completed work.
            assertThat(operation.runRelationTasks(List.of(), task -> { throw new AssertionError(); })).isEmpty();
        }
        assertThat(InProcessAnalysisOperation.current()).isEmpty();

        assertThat(events.progress).extracting(AnalysisProgressEvent::sequence)
                .isSorted().doesNotHaveDuplicates();
        assertThat(events.progress).allSatisfy(e -> assertThat(e.totalTasks()).isEqualTo(4));
        assertThat(events.progress.get(events.progress.size() - 1).phase())
                .isEqualTo(AnalysisProgressPhase.OPERATION_COMPLETED);
        assertThat(events.progress.get(events.progress.size() - 1).completedTasks()).isEqualTo(4);
    }

    @Test
    void cooperativeStopEndsDispatchAndBlocksRelations() {
        var events = new Recorder();
        try (var operation = InProcessAnalysisOperation.open(context("op-b"), events)) {
            operation.plan(ROOTS, true);
            var completions = operation.runSubtaxonomyTasks(task -> task.root().code().equals("CP")
                    ? operation.messages().completed(task, AnalysisTaskOutcome.STOPPED, null, 0, "CANCELLED")
                    : operation.messages().completed(task, AnalysisTaskOutcome.COMPLETED, 5, 1, null));
            assertThat(completions).extracting(c -> c.root().code()).containsExactly("BP", "CP");
            assertThat(operation.stopped()).isTrue();
            assertThat(operation.dispatched()).doesNotContain(AnalysisTaskId.subtaxonomy("op-b",
                    TaxonomyShardRoot.of("CR")));
            assertThat(operation.runRelationTasks(List.of(), task -> { throw new AssertionError(); })).isEmpty();
        }
        assertThat(events.progress).extracting(AnalysisProgressEvent::phase)
                .contains(AnalysisProgressPhase.OPERATION_STOPPED)
                .doesNotContain(AnalysisProgressPhase.OPERATION_COMPLETED);
    }

    @Test
    void relationOnlyFallbackIsUsedWhenScoringRanOutsideTheGraph() {
        try (var operation = InProcessAnalysisOperation.open(context("op-c"), null)) {
            var relations = operation.runRelationTasks(List.of(TaxonomyShardRoot.of("CP")), task ->
                    operation.messages().completed(task, AnalysisTaskOutcome.COMPLETED, 0, null));
            assertThat(relations).singleElement().satisfies(r ->
                    assertThat(r.taskId().value()).isEqualTo("op-c:relation:CP"));
            assertThat(operation.canPlan()).isFalse();
            assertThatThrownBy(() -> operation.plan(ROOTS, false)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void nestedOperationsRestoreTheOuterBinding() {
        try (var outer = InProcessAnalysisOperation.open(context("outer"), null)) {
            try (var inner = InProcessAnalysisOperation.open(context("inner"), null)) {
                assertThat(InProcessAnalysisOperation.current()).containsSame(inner);
            }
            assertThat(InProcessAnalysisOperation.current()).containsSame(outer);
        }
        assertThat(InProcessAnalysisOperation.current()).isEmpty();
    }

    @Test
    void transportAppliesEffectsIdempotentlyByTaskId() {
        var messages = new AnalysisMessageFactory(context("op-d"), java.time.Clock.systemUTC());
        var graph = AnalysisTaskGraph.plan("op-d", List.of(TaxonomyShardRoot.of("BP")), false);
        var task = (SubtaxonomyAnalysisTask) messages.task(graph.tasks().get(0));
        var published = new ArrayList<AnalysisCompletionMessage>();
        int[] executions = {0};
        var transport = new InProcessAnalysisTaskTransport(t -> {
            executions[0]++;
            return messages.completed(t, AnalysisTaskOutcome.COMPLETED, 40, 1, null);
        }, null, published::add);

        transport.publish(task);
        transport.publish(new SubtaxonomyAnalysisTask(task.envelope().nextAttempt(), task.root()));

        assertThat(executions[0]).isEqualTo(1);
        assertThat(transport.executions()).isEqualTo(1);
        assertThat(transport.idempotentReplays()).isEqualTo(1);
        assertThat(published).hasSize(2).allSatisfy(c -> assertThat(c).isSameAs(published.get(0)));
    }

    @Test
    void transportRejectsMissingHandlersAndForeignCompletions() {
        var messages = new AnalysisMessageFactory(context("op-e"), java.time.Clock.systemUTC());
        var graph = AnalysisTaskGraph.plan("op-e", List.of(TaxonomyShardRoot.of("BP"), TaxonomyShardRoot.of("CP")),
                true);
        var bp = (SubtaxonomyAnalysisTask) messages.task(graph.tasks().get(0));
        var cp = (SubtaxonomyAnalysisTask) messages.task(graph.tasks().get(1));
        var transport = new InProcessAnalysisTaskTransport(
                t -> messages.completed(cp, AnalysisTaskOutcome.COMPLETED, 1, 1, null), null, c -> { });
        assertThatThrownBy(() -> transport.publish(bp)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transport.publish(messages.task(graph.tasks().get(2))))
                .isInstanceOf(IllegalStateException.class);
    }
}
