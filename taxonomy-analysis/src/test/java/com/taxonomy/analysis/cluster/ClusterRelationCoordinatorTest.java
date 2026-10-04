package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.*;
import static com.taxonomy.dto.RelationSearchModel.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterRelationCoordinatorTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void scopedHistoryCannotBeHiddenByNewerOperationsInAnotherWorkspace() {
        try (var db = new Database()) {
            var context = context("old-in-scope"); db.admit(context);
            var workspace = new WorkspaceContext("alice", "other", "draft", "repo");
            var original = command("requirement");
            var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice", workspace, null, original.analysisScope());
            for (int n = 0; n < 201; n++) {
                var other = new AnalysisOperationContext("newer-" + n, new AnalysisSourceAuthority("repo", "other", "draft", "source"), context.requirement(), "newer-" + n);
                db.store.admit(other, command, null, Map.of(CP, "{}", IP, "{}"));
            }
            assertEquals(List.of(context), db.store.recent("alice", original.workspaceContext(), null, null));
        }
    }

    @Test void cooperativeWorkerStopPreventsNewRelationDispatchAndPreservesUnexecutedRoots() {
        try (var db = new Database()) {
            var context = context("memory-stop"); admit(db, context);
            var task = db.task(CP);
            var stopped = new AnalysisMessageFactory(context, Clock.systemUTC()).completed(task, AnalysisTaskOutcome.STOPPED, 20, 1, "MEMORY_PRESSURE");
            db.completions.commit(new PreparedAnalysisCompletion<>(stopped, () -> db.store.persistResult(task, result("CP", 20))));
            db.store.accept(stopped);
            assertEquals(ClusterAnalysisState.PARTIAL, db.store.snapshot(context).state());
            assertEquals(Map.of("CP", 20), db.store.snapshot(context).result().getRawScores());
            assertFalse(db.store.start(db.task(IP)).executable());
            assertEquals(2, db.sent.size());
        }
    }

    @Test void requestedArchitectureIsStagedUntilFinalizationAndCancellationWinsThatRace() {
        try (var db = new Database()) {
            var context = context("finalizing"); var original = command("requirement");
            var command = new AnalyzeRequirementCommand(original.businessText(), true, 20, "MOCK", "alice", original.workspaceContext(), null, original.analysisScope());
            var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
            TaxonomyShardRoot.DEFAULT_ROOTS.forEach(r -> targets.put(r, "{}"));
            db.store.admit(context, command, null, Map.of(CP, "{}", IP, "{}"), targets);
            for (var root : List.of(CP, IP)) db.store.accept(db.complete(db.task(root), result(root.code(), 20)));
            var staged = db.store.snapshot(context);
            assertEquals(ClusterAnalysisState.FINALIZING, staged.state());
            assertFalse(staged.state().terminal());
            assertEquals(command, db.store.command(context));
            assertEquals(2, staged.result().getRawScores().size());
            var changed = new AnalysisResult(Map.of("CP", 100), List.of());
            assertThrows(IllegalArgumentException.class, () -> db.store.finalizeResult(context, changed));
            db.store.finalizeResult(context, staged.result());
            assertEquals(ClusterAnalysisState.COMPLETED, db.store.snapshot(context).state());
            long revision = db.store.snapshot(context).revision();
            db.store.finalizeResult(context, staged.result());
            assertEquals(revision, db.store.snapshot(context).revision());
        }
    }

    @Test void sourceCohortSchedulesPreparationOnceThenIndependentTargetWorkAndStableAggregation() {
        try (var db = new Database()) {
            var context = context("relation-coordinator"); admit(db, context);
            db.store.accept(db.complete(db.task(CP), result("CP", 40)));
            assertEquals(2, db.sent.size(), "The selected source cohort is not complete");
            var lastRoot = db.complete(db.task(IP), result("IP", 80));
            db.store.accept(lastRoot); db.store.accept(lastRoot);
            assertEquals(3, db.sent.size());
            var preparation = (RelationAnalysisTask) db.sent.getLast();
            assertNull(preparation.routingRoot());
            assertEquals(2, preparation.prerequisiteTasks().size());
            assertEquals("{\"root\":\"UA\"}", db.store.shard(context, TaxonomyShardRoot.of("UA")));
            var plan = plan(context, preparation);
            var prepared = completion(context, preparation);
            db.completions.commit(new PreparedAnalysisCompletion<>(prepared, () -> db.store.persistRelationPlan(preparation, plan)));
            db.store.accept(prepared); db.store.accept(prepared);
            assertEquals(5, db.sent.size(), "Both fully funded target grants dispatched exactly once");
            List<RelationAnalysisTask> tasks = db.sent.subList(3, 5).stream().map(RelationAnalysisTask.class::cast).toList();
            for (var task : tasks.reversed()) {
                assertEquals(2, task.envelope().schemaVersion());
                var work = JSON.readValue(db.store.start(task).taskInput(), RelationSearchDistribution.Work.class);
                assertEquals(work.ordinal(), task.taskId().relationWorkOrdinal().orElseThrow());
                var result = new RelationSearchDistribution.WorkResult(work, new Result(List.of(), List.of(), List.of(), 1, 1, 1), 1, "");
                var completed = completion(context, task);
                db.completions.commit(new PreparedAnalysisCompletion<>(completed, () -> db.store.persistRelationResult(task, result)));
                db.store.accept(completed); db.store.accept(completed);
            }
            var snapshot = db.store.snapshot(context);
            assertEquals(ClusterAnalysisState.COMPLETED, snapshot.state());
            assertEquals(Map.of("CP", 40, "IP", 80), snapshot.result().getRawScores());
            assertTrue(snapshot.result().getRelationSearchReport().isSearchExhausted());
            assertEquals(3, snapshot.result().getRelationSearchReport().totalCalls());
            assertEquals(5, db.sent.size());
        }
    }

    @Test void relationFailureAndCancelledPreparationRemainExplicitAndCannotReleaseNewWork() {
        try (var db = new Database()) {
            var context = context("relation-failure"); admit(db, context);
            for (var root : List.of(CP, IP)) db.store.accept(db.complete(db.task(root), result(root.code(), 20)));
            var task = (RelationAnalysisTask) db.sent.getLast();
            var failed = new AnalysisMessageFactory(context, Clock.systemUTC()).completed(task, AnalysisTaskOutcome.FAILED, 0, null);
            db.completions.commit(new PreparedAnalysisCompletion<>(failed, () -> db.store.persistRelationFailure(task, "RELATION_PREPARATION_FAILED")));
            db.store.accept(failed);
            assertEquals(ClusterAnalysisState.PARTIAL, db.store.snapshot(context).state());
            assertTrue(db.store.snapshot(context).result().getWarnings().stream().anyMatch(s -> s.contains("RELATION_PREPARATION_FAILED")));
            assertEquals(3, db.sent.size());
        }
        try (var db = new Database()) {
            var context = context("relation-cancelled"); admit(db, context);
            for (var root : List.of(CP, IP)) db.store.accept(db.complete(db.task(root), result(root.code(), 20)));
            var task = (RelationAnalysisTask) db.sent.getLast(); var plan = plan(context, task);
            db.store.cancel(context);
            var completed = completion(context, task);
            db.completions.commit(new PreparedAnalysisCompletion<>(completed, () -> db.store.persistRelationPlan(task, plan)));
            assertFalse(db.store.accept(completed));
            assertEquals(3, db.sent.size());
            assertEquals(ClusterAnalysisState.CANCELLED, db.store.snapshot(context).state());
        }
    }

    @Test void durableLookupDistinguishesAbsentFromForeignWithoutFallingBackAndFiltersRecentScope() {
        try (var db = new Database()) {
            var context = context("owned"); db.admit(context);
            var workspace = command("requirement").workspaceContext();
            assertEquals(context, db.store.findAuthorized("owned", "alice", workspace).orElseThrow());
            assertTrue(db.store.findAuthorized("absent", "alice", workspace).isEmpty());
            assertThrows(SecurityException.class, () -> db.store.findAuthorized("owned", "bob", workspace));
            assertThrows(SecurityException.class, () -> db.store.findAuthorized("owned", "alice", new WorkspaceContext("alice", "workspace", "other", "repo")));
            assertEquals(List.of(context), db.store.recent("alice", workspace, null, null));
            assertTrue(db.store.recent("alice", workspace, 123L, null).isEmpty());
        }
    }

    static void admit(Database db, AnalysisOperationContext context) {
        var original = command("requirement");
        var command = new AnalyzeRequirementCommand(original.businessText(), false, 20, "MOCK", "alice", original.workspaceContext(), null,
                new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.FULL));
        var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
        TaxonomyShardRoot.DEFAULT_ROOTS.forEach(r -> targets.put(r, "{\"root\":\"" + r.code() + "\"}"));
        db.store.admit(context, command, null, Map.of(CP, targets.get(CP), IP, targets.get(IP)), targets);
    }
    static RelationSearchDistribution.Plan plan(AnalysisOperationContext context, RelationAnalysisTask task) {
        var node = new Node("source", "CP", "Capability", "description", false);
        var contribution = new Contribution(node, "requirement", "requirement", "");
        var sources = List.of(new SourceAssessment(node, List.of(contribution), "", ""));
        return new RelationSearchDistribution.Plan(1, context.operationId() + ":relations", context.requirement().textSha256(),
                task.prerequisiteTasks().stream().map(AnalysisTaskId::value).toList(),
                new RequirementRelationSearch.Options(new Limits(10, 8, 10, 128), 16), List.of(node),
                List.of(new Node("CP", "CP", "Capabilities", "", true), new Node("IP", "IP", "Products", "", true)), sources,
                List.of(new RelationSearchDistribution.Item(0, 0, 0, "REQUIRES", Direction.OUTGOING, CP, 1),
                        new RelationSearchDistribution.Item(1, 0, 0, "REQUIRES", Direction.OUTGOING, IP, 1)), 1, 0, List.of(), "", true);
    }
    static RelationAnalysisCompleted completion(AnalysisOperationContext context, RelationAnalysisTask task) {
        return new AnalysisMessageFactory(context, Clock.systemUTC()).completed(task, AnalysisTaskOutcome.COMPLETED, 0, null);
    }
}
