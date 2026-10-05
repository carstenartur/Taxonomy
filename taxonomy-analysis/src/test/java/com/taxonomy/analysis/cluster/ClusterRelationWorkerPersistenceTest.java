package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real Hibernate/ledger integration around the production frozen relation worker. */
class ClusterRelationWorkerPersistenceTest {
    @Test void scoreOnlyFallbackCommitsIdempotentlyWithoutHierarchicalReportOrTargetDispatch() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var fixture = new FrozenClusterRelationComputationTest.Fixture(); fixture.productScores();
            ReflectionTestUtils.setField(fixture.relations, "enabled", false);
            var context = fixture.store.context; var original = fixture.store.command;
            var command = new AnalyzeRequirementCommand(original.businessText(), false, 20, "MOCK", original.username(),
                    original.workspaceContext(), null, new AnalysisScope(Set.of("BP", "IP"), AnalysisMode.FULL));
            var snapshots = new LinkedHashMap<TaxonomyShardRoot, String>();
            fixture.store.snapshots.forEach((root, json) -> snapshots.put(TaxonomyShardRoot.of(root), json));
            var bp = TaxonomyShardRoot.of("BP"); var ip = TaxonomyShardRoot.of("IP");
            db.store.admit(context, command, null, Map.of(bp, snapshots.get(bp), ip, snapshots.get(ip)), snapshots);
            var source = new AnalysisResult(Map.of("process", 90), List.of()); source.setStatus("SUCCESS");
            var target = new AnalysisResult(Map.of("family", 10, "product", 100, "evidence", 80), List.of()); target.setStatus("SUCCESS");
            target.setScoreSemanticsContext(fixture.store.roots.getScoreSemanticsContext());
            db.store.accept(db.complete(db.task(bp), source)); db.store.accept(db.complete(db.task(ip), target));
            var task = (RelationAnalysisTask) db.sent.getLast();
            var store = adapter(db.store);
            var service = new ClusterRelationService(store, fixture.computation(store, "all", "BP,BR,CP,CI,CO,CR,IP,UA"), new ClusterAnalysisSignals());
            var prepared = service.prepare(task);
            assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.COMPLETED);
            assertThat(prepared.completion().relationCount()).isPositive();
            assertThat(fixture.prompts).isEmpty();
            assertThatThrownBy(() -> db.completions.commit(new PreparedAnalysisCompletion<>(prepared.completion(), () -> {
                prepared.persistEffect().run(); throw new IllegalStateException("rollback score-only evidence");
            }))).isInstanceOf(IllegalStateException.class);
            assertThat(db.store.rootResults(context).getProvisionalRelations()).isEmpty();
            var completed = db.completions.commit(prepared);
            assertThat(db.completions.commit(prepared)).isEqualTo(completed);
            assertThat(db.store.accept(completed)).isTrue();
            assertThat(db.store.accept(completed)).isFalse();
            var snapshot = db.store.snapshot(context);
            assertThat(snapshot.state()).isEqualTo(ClusterAnalysisState.COMPLETED);
            assertThat(snapshot.result().getRelationSearchReport()).isNull();
            assertThat(snapshot.result().getProvisionalRelations()).isNotEmpty().allSatisfy(hypothesis -> {
                assertThat(hypothesis.getSourceCode()).isNotEqualTo("product");
                assertThat(hypothesis.getTargetCode()).isNotEqualTo("product");
                assertThat(hypothesis.getConfidence()).isEqualTo(0.72);
            });
            assertThat(db.sent).hasSize(3);
            assertThat(fixture.prompts).isEmpty();
        }
    }

    @Test void preparedSourceAndReverseTargetCompletionsCommitOnceWithRollbackAndNoExtractionReplay() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var fixture = new FrozenClusterRelationComputationTest.Fixture();
            var context = fixture.store.context;
            var original = fixture.store.command;
            var command = new AnalyzeRequirementCommand(original.businessText(), false, 20, "MOCK", original.username(),
                    original.workspaceContext(), null, new AnalysisScope(Set.of("BP", "IP"), AnalysisMode.FULL));
            var snapshots = new LinkedHashMap<TaxonomyShardRoot, String>();
            fixture.store.snapshots.forEach((root, json) -> snapshots.put(TaxonomyShardRoot.of(root), json));
            var bp = TaxonomyShardRoot.of("BP"); var ip = TaxonomyShardRoot.of("IP");
            db.store.admit(context, command, null, Map.of(bp, snapshots.get(bp), ip, snapshots.get(ip)), snapshots);
            var source = new AnalysisResult(Map.of("process", 70), List.of()); source.setStatus("SUCCESS");
            db.store.accept(db.complete(db.task(bp), source));
            db.store.accept(db.complete(db.task(ip), ClusterAnalysisStoreTest.result("IP", 0)));
            var task = (RelationAnalysisTask) db.sent.getLast();
            var store = adapter(db.store);
            var signals = new ClusterAnalysisSignals();
            var service = new ClusterRelationService(store, fixture.computation(store, "all", "BP,BR,CP,CI,CO,CR,IP,UA"), signals);
            var prepared = service.prepare(task);
            assertThat(prepared.completion().outcome()).isEqualTo(AnalysisTaskOutcome.COMPLETED);
            assertThatThrownBy(() -> db.store.relationPlan(context)).isInstanceOf(IllegalStateException.class);
            int preparationCalls = fixture.prompts.size();
            assertThatThrownBy(() -> db.completions.commit(new PreparedAnalysisCompletion<>(prepared.completion(), () -> {
                prepared.persistEffect().run(); throw new IllegalStateException("crash before ledger commit");
            }))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.store.relationPlan(context)).isInstanceOf(IllegalStateException.class);
            var completed = db.completions.commit(prepared);
            db.store.accept(completed);
            assertThat(db.completions.commit(prepared)).isEqualTo(completed);
            assertThat(db.store.accept(completed)).isFalse();
            assertThat(fixture.prompts).hasSize(preparationCalls);
            Set<AnalysisTaskId> processed = new HashSet<>();
            while (true) {
                var ready = db.sent.stream().filter(t -> t instanceof RelationAnalysisTask
                                && t.taskId().relationWorkOrdinal().isPresent() && !processed.contains(t.taskId()))
                        .map(RelationAnalysisTask.class::cast).toList().reversed();
                if (ready.isEmpty()) break;
                for (var target : ready) {
                    var worker = new ClusterRelationService(store, fixture.computation(store, "worker", target.routingRoot().code()), signals);
                    var evaluated = worker.prepare(target);
                    assertThat(evaluated.completion().outcome()).isEqualTo(AnalysisTaskOutcome.COMPLETED);
                    var completion = db.completions.commit(evaluated);
                    assertThat(db.completions.commit(evaluated)).isEqualTo(completion);
                    assertThat(db.store.accept(completion)).isTrue();
                    assertThat(db.store.accept(completion)).isFalse();
                    processed.add(target.taskId());
                }
            }
            var snapshot = db.store.snapshot(context);
            assertThat(snapshot.state()).isEqualTo(ClusterAnalysisState.COMPLETED);
            assertThat(snapshot.result().getRawScores()).containsExactlyInAnyOrderEntriesOf(Map.of("process", 70, "IP", 0));
            var report = snapshot.result().getRelationSearchReport();
            assertThat(report.isSearchExhausted()).isTrue();
            assertThat(report.result().edges()).hasSize(1);
            assertThat(report.totalCalls()).isEqualTo(fixture.prompts.size()).isLessThanOrEqualTo(24);
            assertThat(fixture.prompts.stream().filter(p -> p.contains("Extract ONLY")).count()).isEqualTo(1);
            assertThat(signals.workerCount()).isZero();
        }
    }

    private static ClusterRelationService.Store adapter(ClusterAnalysisStore store) {
        return new ClusterRelationService.Store() {
            public ClusterAnalysisStore.Input start(AnalysisTaskMessage task) { return store.start(task); }
            public String shard(AnalysisOperationContext context, TaxonomyShardRoot root) { return store.shard(context, root); }
            public AnalysisResult rootResults(AnalysisOperationContext context) { return store.rootResults(context); }
            public RelationSearchDistribution.Plan relationPlan(AnalysisOperationContext context) { return store.relationPlan(context); }
            public void persistRelationPlan(RelationAnalysisTask task, RelationSearchDistribution.Plan plan) { store.persistRelationPlan(task, plan); }
            public void persistRelationResult(RelationAnalysisTask task, RelationSearchDistribution.WorkResult result) { store.persistRelationResult(task, result); }
            public void persistScoreOnlyRelations(RelationAnalysisTask task, List<RelationHypothesisDto> value) { store.persistScoreOnlyRelations(task, value); }
            public void persistRelationFailure(RelationAnalysisTask task, String reason) { store.persistRelationFailure(task, reason); }
        };
    }
}
