package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisDetailedProgressTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");

    @Test void liveRelationProgressPreservesTheAdmittedCumulativeBudget() {
        var plan = budgetPlan(10);
        var view = ClusterAnalysisProgress.combine(context("budget-progress"), ClusterAnalysisState.RUNNING,
                List.of(), Map.of(), new ObjectMapper().writeValueAsString(plan), null, new ObjectMapper());
        assertNotNull(view.relations().budget(), "Polling must retain the cumulative budget carried by the plan");
        assertEquals(4, view.relations().budget().spentCalls());
        assertEquals(256, view.relations().budget().callsPerSlice());
        assertEquals(4096, view.relations().budget().maxTotalCalls());
    }

    @Test void anUnstartedContinuationPreservesAssessedSourcesAndUnresolvedSearches() {
        var plan = budgetPlan(0);
        var source = plan.sourceNodes().getFirst();
        var previous = new RelationSearchReport(2, "digest", "policy",
                List.of(new RelationSearchModel.SourceAssessment(source, List.of(), "The dependency is unclear", "Which dependency is required?")),
                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 0),
                1, 10, 0, List.of("SOURCE_UNRESOLVED"), "", null,
                List.of(new RelationSearchProgress.Task(source.id(), "IP", RelationSearchProgress.State.UNRESOLVED)));
        var view = ClusterAnalysisProgress.combine(context("retained-progress"), ClusterAnalysisState.RUNNING,
                List.of(), Map.of(), new ObjectMapper().writeValueAsString(plan), previous, new ObjectMapper());
        assertEquals(1, view.relations().assessedSources(), "A new grant must preserve saved source evidence before replay");
        assertEquals(1, view.relations().unresolvedSearches(), "An unresolved question cannot revert to an unassessed search");
        assertEquals(RelationSearchProgress.Step.PAUSED, view.relations().step());
    }

    private static RelationSearchDistribution.Plan budgetPlan(int calls) {
        var source = new RelationSearchModel.Node("source", "CP", "Source", "", false);
        return new RelationSearchDistribution.Plan(1, "budget-plan", "digest", List.of("source-result"),
                new com.taxonomy.analysis.relations.RequirementRelationSearch.Options(new RelationSearchModel.Limits(calls, 8, 10, 4096),
                        128, 16000, new com.taxonomy.analysis.relations.RequirementRelationSearch.BudgetPolicy(256, 4096, 4, true, 128, 4096, 128)),
                List.of(source), List.of(new RelationSearchModel.Node("IP", "IP", "Products", "", true)),
                List.of(), List.of(), 0, 0, List.of(), "", true);
    }

    private static SubtaxonomyAnalysisTask releaseIpAfterUnassessedCp(Database db) {
        var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
        db.store.accept(db.complete(db.task(CP), failed));
        return db.task(IP);
    }

    @Test void relationAggregationNeverPublishesProgressIntoTheCallingWorker() {
        var node = new RelationSearchModel.Node("source", "CP", "Source", "", false);
        var contribution = new RelationSearchModel.Contribution(node, "requirement", "requirement", "");
        var plan = new RelationSearchDistribution.Plan(1, "plan", "digest", List.of("source-result"),
                new com.taxonomy.analysis.relations.RequirementRelationSearch.Options(new RelationSearchModel.Limits(10, 8, 10, 128), 16),
                List.of(node), List.of(new RelationSearchModel.Node("IP", "IP", "Products", "", true)),
                List.of(new RelationSearchModel.SourceAssessment(node, List.of(contribution), "", "")),
                List.of(new RelationSearchDistribution.Item(0, 0, 0, "REQUIRES", RelationSearchModel.Direction.OUTGOING, IP, 1)),
                1, 0, List.of(), "", true);
        var publications = new java.util.concurrent.atomic.AtomicInteger();
        var observer = new com.taxonomy.analysis.service.AnalysisRunControl.Observer() {
            public void phase(String phase, String node) { }
            public long started(String provider, String node) { return 0; }
            public void completed(long id, LlmCallDetail detail, long duration) { }
            public void failed(long id, String failure, long duration) { }
            public void stopped(com.taxonomy.analysis.service.AnalysisStoppedException.Reason reason) { }
            public void relations(RelationSearchProgress progress) { publications.incrementAndGet(); }
        };
        var guard = new com.taxonomy.analysis.service.AnalysisMemoryGuard(
                new com.taxonomy.analysis.service.AnalysisMemoryGuard.Policy(80, 90, 1024 * 1024, 1000, 60000),
                () -> new com.taxonomy.analysis.service.AnalysisMemoryGuard.Sample(1024, 100_000_000), System::currentTimeMillis);
        try (var worker = com.taxonomy.analysis.service.AnalysisRunControl.worker(observer, () -> false, guard)) {
            RelationSearchDistribution.combine(plan, List.of());
        }
        assertEquals(0, publications.get(), "An observation read cannot write telemetry or acquire a new worker transaction");
    }

    @Test void frozenCatalogueCoverageExistsBeforeAnyWorkerOrProviderCall() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("planned-progress"); db.admitFrozen(context);
            var progress = snapshot(observation, context).nodeProgress();
            assertNotNull(progress, "Artemis must expose the admitted catalogue before work starts");
            assertEquals(3, progress.total()); assertEquals(3, progress.open());
            assertEquals(List.of("CP", "IP"), progress.taxonomies().stream().map(AnalysisNodeProgress.Taxonomy::root).toList());
            assertEquals(List.of(1, 2), progress.taxonomies().stream().map(AnalysisNodeProgress.Taxonomy::total).toList());
        }
    }

    @Test void distributedSnapshotsCountPositiveNodesAndLeafWeightFromFrozenWorkerEvidence() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("score-progress"); db.admitFrozen(context);
            var task = releaseIpAfterUnassessedCp(db); var input = db.store.start(task);
            var worker = db.store.diagnostics(task, input.deliveryAttempt());
            var scores = new LlmCallDetail();
            scores.setScores(Map.of("IP", 80d, "fixture-ip-child", 0.125d));
            worker.assessment(scores);
            var progress = snapshot(observation, context).nodeProgress();
            var ip = progress.taxonomies().stream().filter(part -> part.root().equals("IP")).findFirst().orElseThrow();
            assertEquals(2, ip.positive());
            assertEquals(0.125d, ip.allocatedPercent());
            assertEquals(80d, ip.rootPercent());
            var cp = progress.taxonomies().stream().filter(part -> part.root().equals("CP")).findFirst().orElseThrow();
            assertEquals(0, cp.positive());
            assertNull(cp.rootPercent(), "An unassessed root has an unknown budget, not a synthetic zero");
            db.store.cancel(context);
            assertEquals(progress, snapshot(observation, context).nodeProgress());
        }
    }

    @Test void liveAssessmentsDeduplicateByFrozenIdentityAndPersistProviderWaitReasons() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("live-progress"); db.admitFrozen(context);
            var task = releaseIpAfterUnassessedCp(db); var input = db.store.start(task);
            var worker = db.store.diagnostics(task, input.deliveryAttempt());
            worker.assessment(scores(Map.of("IP", 0, "foreign", 80)));
            worker.phase("WAITING_PROVIDER_CAPACITY", "IP");
            var live = snapshot(observation, context);
            assertNotNull(live.nodeProgress()); assertEquals(1, live.nodeProgress().assessed());
            assertEquals(1, live.nodeProgress().excluded()); assertEquals(1, live.nodeProgress().open());
            assertEquals("WAITING_PROVIDER_CAPACITY", live.phase()); assertEquals("IP", live.node());
            long revision = live.sequence();
            worker.assessment(scores(Map.of("IP", 0, "foreign", 80)));
            assertEquals(revision, snapshot(observation, context).sequence(), "Repeated evidence must not invent progress events");
            worker.assessment(scores(Map.of("fixture-ip-child", 35)));
            assertEquals(2, snapshot(observation, context).nodeProgress().assessed());
            assertEquals(0, snapshot(observation, context).nodeProgress().excluded());
            db.store.cancel(context); var terminal = snapshot(observation, context);
            assertThrows(IllegalStateException.class, () -> worker.assessment(scores(Map.of("IP", 90))));
            assertThrows(IllegalStateException.class, () -> worker.phase("SCORING", "IP"));
            assertEquals(terminal.nodeProgress(), snapshot(observation, context).nodeProgress());
            assertEquals(terminal.sequence(), snapshot(observation, context).sequence());
        }
    }

    @Test void ordinaryWorkerCallsPublishParsedCoverageBeforeARootCommitsAndIgnoreSupersededResponses() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("ordinary-call-progress"); db.admitFrozen(context); var task = releaseIpAfterUnassessedCp(db);
            var input = db.store.start(task); var first = db.store.diagnostics(task, input.deliveryAttempt());
            var guard = new com.taxonomy.analysis.service.AnalysisMemoryGuard(
                    new com.taxonomy.analysis.service.AnalysisMemoryGuard.Policy(80, 90, 1024 * 1024, 1000, 60000),
                    () -> new com.taxonomy.analysis.service.AnalysisMemoryGuard.Sample(1024, 100_000_000), System::currentTimeMillis);
            try (var worker = com.taxonomy.analysis.service.AnalysisRunControl.worker(first, () -> false, guard)) {
                com.taxonomy.analysis.service.AnalysisRunControl.call("Mock", "IP", () -> scores(Map.of("IP", 0)));
            }
            var live = snapshot(observation, context).nodeProgress();
            assertEquals(1, live.assessed(), "Ordinary calls must publish coverage without a continuation replay callback");
            assertEquals(1, live.excluded()); assertNull(db.store.snapshot(context).result());
            long lateCall = first.started("Mock", "IP");
            var next = db.store.start(task); var second = db.store.diagnostics(task, next.deliveryAttempt());
            second.assessment(scores(Map.of("IP", 85))); var current = snapshot(observation, context).nodeProgress();
            first.completed(lateCall, scores(Map.of("IP", 0)), 3);
            assertEquals(current, snapshot(observation, context).nodeProgress(), "A superseded response can retain diagnostics but cannot replace progress");
        }
    }

    @Test void supersededAndDuplicateSessionsCannotOverwriteNewerCoverageOrWaitState() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("fenced-progress"); db.admitFrozen(context); var task = releaseIpAfterUnassessedCp(db);
            var firstInput = db.store.start(task); var first = db.store.diagnostics(task, firstInput.deliveryAttempt());
            first.assessment(scores(Map.of("IP", 80)));
            var secondInput = db.store.start(task); var second = db.store.diagnostics(task, secondInput.deliveryAttempt());
            second.assessment(scores(Map.of("IP", 0))); second.phase("WAITING_RATE_LIMIT", "IP");
            var before = snapshot(observation, context);
            assertThrows(IllegalStateException.class, () -> first.assessment(scores(Map.of("IP", 90))));
            assertThrows(IllegalStateException.class, () -> first.phase("SCORING", "IP"));
            assertThrows(IllegalStateException.class, () -> db.store.diagnostics(task, secondInput.deliveryAttempt()));
            assertEquals(before.nodeProgress(), snapshot(observation, context).nodeProgress());
            assertEquals(before.phase(), snapshot(observation, context).phase());
            assertEquals(before.sequence(), snapshot(observation, context).sequence());
        }
    }

    @Test void runningCoverageSurvivesPersistenceRestartAndCancellationLeavesUnassessedWorkOpen() {
        String url = "jdbc:hsqldb:mem:progress-restart-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
        var context = context("progress-restart"); AnalysisNodeProgress expected;
        try (var db = new Database(url, "create", sql -> sql); var observation = observation(db.store)) {
            db.admitFrozen(context); var task = db.task(CP); var input = db.store.start(task);
            var worker = db.store.diagnostics(task, input.deliveryAttempt()); worker.assessment(scores(Map.of("CP", 70)));
            worker.phase("WAITING_RATE_LIMIT", "CP"); expected = snapshot(observation, context).nodeProgress();
            assertNotNull(expected); assertEquals(2, expected.open());
        }
        try (var db = new Database(url, "validate", sql -> sql); var observation = observation(db.store)) {
            assertEquals(expected, snapshot(observation, context).nodeProgress());
            assertEquals("WAITING_RATE_LIMIT", snapshot(observation, context).phase());
            observation.cancel(context.operationId(), "alice", command("requirement").workspaceContext());
            var cancelled = snapshot(observation, context); assertEquals("CANCELLED", cancelled.status());
            assertEquals(expected, cancelled.nodeProgress()); assertEquals(2, cancelled.nodeProgress().open());
            assertEquals("FINISHED", cancelled.phase());
        }
    }

    @Test void committedPartialAndFailedShardsRetainOpenCoverageWithoutInventingZeroScores() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("terminal-progress"); db.admitFrozen(context);
            var partial = result("CP", 70); partial.setStatus("PARTIAL");
            db.store.accept(db.complete(db.task(CP), partial));
            var failed = new AnalysisResult(Map.of(), List.of()); failed.setStatus("ERROR");
            db.store.accept(db.complete(db.task(IP), failed));
            var terminal = snapshot(observation, context);
            assertEquals("PARTIAL", terminal.status()); assertNotNull(terminal.nodeProgress());
            assertEquals(1, terminal.nodeProgress().assessed()); assertEquals(2, terminal.nodeProgress().open());
            assertEquals(Map.of("CP", 70d), terminal.rawScores());
        }
    }

    @Test void distributedRelationsExposeLiveSourceAndTargetWorkAndKeepCancelledSearchesPending() {
        var fixture = new FrozenClusterRelationComputationTest.Fixture();
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = fixture.store.context; var original = fixture.store.command;
            var request = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice", original.workspaceContext(), null,
                    new AnalysisScope(Set.of("BP"), AnalysisMode.FULL));
            var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
            TaxonomyShardRoot.DEFAULT_ROOTS.forEach(root -> targets.put(root, fixture.store.snapshots.get(root.code())));
            db.store.admit(context, request, null, Map.of(TaxonomyShardRoot.of("BP"), targets.get(TaxonomyShardRoot.of("BP"))), targets);
            fixture.store.roots.setStatus("SUCCESS"); var root = db.task(TaxonomyShardRoot.of("BP"));
            db.store.start(root); db.store.accept(db.complete(root, fixture.store.roots));
            var preparation = (RelationAnalysisTask) db.sent.stream().filter(t -> t instanceof RelationAnalysisTask).findFirst().orElseThrow();
            var steps = new ArrayList<RelationSearchProgress>();
            fixture.afterResponse = () -> {
                var progress = snapshot(observation, context).relationProgress();
                assertNotNull(progress, "Provider work must publish a durable source/search snapshot before its response");
                steps.add(progress);
            };
            var plan = ((ClusterRelationComputation.Preparation) fixture.computation(db.store, "all", "BP,BR,CP,CI,CO,CR,IP,UA")
                    .compute(db.store.start(preparation), preparation, () -> false)).plan();
            assertTrue(steps.stream().anyMatch(p -> p.step() == RelationSearchProgress.Step.SOURCES));
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> db.store.persistRelationPlan(preparation, plan));
            db.store.accept(ClusterRelationCoordinatorTest.completion(context, preparation));
            var target = (RelationAnalysisTask) db.sent.stream().filter(t -> t instanceof RelationAnalysisTask r
                    && r.taskId().relationWorkOrdinal().isPresent() && r.targetRoots().equals(List.of(IP))).findFirst().orElseThrow();
            var evaluated = (ClusterRelationComputation.Evaluation) fixture.computation(db.store, "worker", "IP")
                    .compute(db.store.start(target), target, () -> false);
            assertTrue(steps.stream().anyMatch(p -> p.current() != null && "IP".equals(p.current().targetRoot())
                    && p.step() == RelationSearchProgress.Step.NAVIGATE), "Target navigation must be observable while its call runs");
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> db.store.persistRelationResult(target, evaluated.result()));
            db.store.accept(ClusterRelationCoordinatorTest.completion(context, target));
            var live = snapshot(observation, context).relationProgress(); assertNotNull(live);
            assertTrue(live.assessedSources() > 0);
            assertEquals(0, live.completedSearches(), "One relation intent cannot complete its other source/target intents");
            assertTrue(live.calls() >= plan.sourceCalls() + evaluated.result().calls());
            assertEquals(live.totalSearches(), live.completedSearches() + live.unresolvedSearches() + live.pendingSearches());
            observation.cancel(context.operationId(), "alice", original.workspaceContext());
            var cancelled = snapshot(observation, context).relationProgress();
            assertEquals(RelationSearchProgress.Step.PAUSED, cancelled.step()); assertNull(cancelled.current());
            assertTrue(cancelled.pendingSearches() > 0, "Cancelled work cannot become completed search work");
            assertEquals(live.completedSearches(), cancelled.completedSearches());
        }
    }

    @Test void acceptedRelationGrantsUpdateTargetCountsOnceAndSurviveANewObserver() {
        try (var db = new Database(); var observation = observation(db.store)) {
            var context = context("committed-relation-progress"); ClusterRelationCoordinatorTest.admit(db, context);
            for (var root : List.of(CP, IP)) db.store.accept(db.complete(db.task(root), result(root.code(), 70)));
            var preparation = (RelationAnalysisTask) db.sent.stream().filter(task -> task instanceof RelationAnalysisTask).findFirst().orElseThrow();
            var base = ClusterRelationCoordinatorTest.plan(context, preparation);
            var serviceRoot = TaxonomyShardRoot.of("CR");
            var plan = new RelationSearchDistribution.Plan(base.schemaVersion(), base.preparationId(), base.originalSha256(),
                    base.sourceResultIds(), base.options(), base.sourceNodes(),
                    List.of(new RelationSearchModel.Node("CR", "CR", "Services", "", true), base.targetRoots().getLast()),
                    base.sources(), List.of(new RelationSearchDistribution.Item(0, 0, 0, "REALIZES", RelationSearchModel.Direction.OUTGOING, serviceRoot, 1),
                    base.items().getLast()), base.sourceCalls(), base.durationMillis(), base.warnings(), base.stopReason(), base.included());
            db.store.start(preparation);
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> db.store.persistRelationPlan(preparation, plan));
            db.store.accept(ClusterRelationCoordinatorTest.completion(context, preparation));
            var target = (RelationAnalysisTask) db.sent.stream().filter(task -> task instanceof RelationAnalysisTask relation
                    && relation.taskId().relationWorkOrdinal().orElse(-1) == 0).findFirst().orElseThrow();
            var input = db.store.start(target); var grant = new ObjectMapper().readValue(input.taskInput(), RelationSearchDistribution.Work.class);
            var effect = new RelationSearchDistribution.WorkResult(grant, new RelationSearchModel.Result(List.of(), List.of(), List.of(), 1, 1, 1), 1, "");
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> db.store.persistRelationResult(target, effect));
            var completion = ClusterRelationCoordinatorTest.completion(context, target);
            assertTrue(db.store.accept(completion)); assertFalse(db.store.accept(completion));
            var progress = snapshot(observation, context).relationProgress(); assertNotNull(progress);
            assertEquals(2, progress.totalSearches()); assertEquals(1, progress.completedSearches()); assertEquals(1, progress.pendingSearches());
            assertEquals(1, progress.taxonomies().stream().filter(root -> root.root().equals("CR")).findFirst().orElseThrow().completed());
            try (var restarted = observation(new ClusterAnalysisStore(db.em, db.transactions, new ObjectMapper(), db.dispatch))) {
                assertEquals(progress, snapshot(restarted, context).relationProgress());
                restarted.cancel(context.operationId(), "alice", command("requirement").workspaceContext());
                var cancelled = snapshot(restarted, context).relationProgress();
                assertEquals(1, cancelled.completedSearches()); assertEquals(1, cancelled.pendingSearches());
                assertEquals(RelationSearchProgress.Step.PAUSED, cancelled.step());
            }
        }
    }

    private static DurableClusterAnalysisObservation observation(ClusterAnalysisStore store) {
        return new DurableClusterAnalysisObservation(store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE);
    }
    private static com.taxonomy.analysis.service.AnalysisProgressRegistry.Snapshot snapshot(DurableClusterAnalysisObservation observation,
                                                                                          AnalysisOperationContext context) {
        return observation.snapshot(context.operationId(), "alice", command("requirement").workspaceContext()).orElseThrow().snapshot();
    }
    private static LlmCallDetail scores(Map<String, Integer> values) { var detail = new LlmCallDetail(); detail.setScores(values); return detail; }
}
