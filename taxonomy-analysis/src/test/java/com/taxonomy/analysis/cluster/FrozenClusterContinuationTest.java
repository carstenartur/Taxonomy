package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RequirementRelationSearch;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.*;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Real frozen worker and durable continuation authority; provider exchanges are deterministic fixtures. */
class FrozenClusterContinuationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveAndTerminalRelationSnapshotsUseTheBoundCallLedgerInsteadOfSchedulerCharges(boolean budgetStop) {
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("claimed-relation-progress");
        var original = ClusterAnalysisStoreTest.command("requirement"); var request = relationRequest();
        var cp = TaxonomyShardRoot.of("CP"); var ip = TaxonomyShardRoot.of("IP");
        var tree = relationTree();
        String signature = AnalysisCheckpointSession.digest("claimed-relation-progress");
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var saved = continuation(db, mapper); var claim = saved.begin(request, original.workspaceContext(), signature, tree);
            for (int call = 0; call < 4; call++) saved.beforeRelationCall(claim, 20);
            saved.freezeRelationSourceCohort(claim, 32);
            var policy = new RequirementRelationSearch.BudgetPolicy(2, 20, 4, true, 128, 4096, 32);
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var targets = relationTargets(context, mapper);
            store.admit(context, relationCommand(request, claim, signature, policy), null,
                    Map.of(cp, targets.get(cp), ip, targets.get(ip)), targets);
            for (var root : List.of(cp, ip)) store.accept(db.complete(db.task(root), ClusterAnalysisStoreTest.result(root.code(), 75)));
            var preparation = (RelationAnalysisTask) db.sent.getLast(); store.start(preparation);
            var base = ClusterRelationCoordinatorTest.plan(context, preparation);
            var plan = new RelationSearchDistribution.Plan(base.schemaVersion(), base.preparationId(), base.originalSha256(),
                    base.sourceResultIds(), new RequirementRelationSearch.Options(base.options().limits(), 128, 16000, policy),
                    base.sourceNodes(), base.targetRoots(), base.sources(), base.items(), base.sourceCalls(), base.durationMillis(),
                    base.warnings(), base.stopReason(), base.included());
            var prepared = ClusterRelationCoordinatorTest.completion(context, preparation);
            db.completions.commit(new PreparedAnalysisCompletion<>(prepared, () -> store.persistRelationPlan(preparation, plan)));
            store.accept(prepared);
            var targetsToRun = db.sent.stream().filter(task -> task instanceof RelationAnalysisTask relation
                    && relation.taskId().relationWorkOrdinal().isPresent()).map(RelationAnalysisTask.class::cast).toList();
            assertThat(targetsToRun).hasSize(2);
            for (int ordinal = 0; ordinal < targetsToRun.size(); ordinal++) {
                var target = targetsToRun.get(ordinal); var input = store.start(target);
                // A later task may replay an accepted response while retaining its original scheduler debit.
                if (ordinal == 0) try (var checkpoint = store.checkpointSession(input, target, signature, signature)) {
                    AnalysisCheckpointSession.beforeRelationCall(20);
                }
                var work = mapper.readValue(input.taskInput(), RelationSearchDistribution.Work.class);
                var effect = budgetStop && ordinal == targetsToRun.size() - 1
                        ? RelationSearchDistribution.failed(plan, work, "CALL_BUDGET")
                        : new RelationSearchDistribution.WorkResult(work,
                                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 1, 1, 1), 1, "");
                var completed = ClusterRelationCoordinatorTest.completion(context, target);
                db.completions.commit(new PreparedAnalysisCompletion<>(completed, () -> store.persistRelationResult(target, effect)));
                store.accept(completed);
                if (ordinal == 0) {
                    var live = store.snapshot(context); assertThat(live.state().terminal()).isFalse();
                    assertThat(live.progress().relations().calls()).isEqualTo(2);
                    assertThat(live.progress().relations().budget().spentCalls()).isEqualTo(5);
                    assertThat(live.progress().relations().budget().newCalls()).isEqualTo(1);
                }
            }
            var terminal = store.snapshot(context);
            assertThat(terminal.state()).isEqualTo(budgetStop ? ClusterAnalysisState.PARTIAL : ClusterAnalysisState.COMPLETED);
            assertThat(terminal.result().getRelationSearchReport().totalCalls()).isEqualTo(3);
            assertThat(terminal.progress().relations().budget().spentCalls()).isEqualTo(5);
            assertThat(terminal.progress().relations().budget().newCalls()).isEqualTo(1);
            assertThat(terminal.result().getRelationSearchReport().budget().spentCalls()).isEqualTo(5);
            assertThat(terminal.result().getRelationSearchReport().budget().newCalls()).isEqualTo(1);
            assertThat(saved.read(claim.id(), original.workspaceContext()).result().getRelationSearchReport().budget())
                    .isEqualTo(terminal.result().getRelationSearchReport().budget());
            if (budgetStop) {
                var stopped = saved.read(claim.id(), original.workspaceContext());
                assertThat(stopped.recovery().state()).isEqualTo("STOPPED");
                request.setContinuationAction("CONTINUE"); request.setContinuationVersion(stopped.recovery().version());
                var next = saved.begin(request, original.workspaceContext(), signature, tree);
                assertThat(next.token()).isNotEqualTo(claim.token());
                saved.beforeRelationCall(next, 20);
                assertThat(saved.relationCallsSpent(next)).isEqualTo(6);
                var historical = store.snapshot(context);
                assertThat(historical.progress().relations()).as("A later claim cannot change committed operation progress")
                        .isEqualTo(terminal.progress().relations());
                assertThat(historical.result().getRelationSearchReport()).isEqualTo(terminal.result().getRelationSearchReport());
            }
        }
    }

    @Test void cancellationBeforePreparationRetainsPriorRelationEvidenceAndTheNewAdmittedBudget() {
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("retained-relation-progress");
        var original = ClusterAnalysisStoreTest.command("requirement"); var request = relationRequest();
        var tree = relationTree();
        var source = new RelationSearchModel.Node("source", "CP", "Source", "", false);
        var oldBudget = new RelationSearchBudget(4, 4, 4, 4, false, 1, 16, 32, 16);
        var progress = new RelationSearchProgress(1, 1, 1, 0, 1, 0, 4, 4, 0,
                RelationSearchProgress.Step.PAUSED, null, List.of(new RelationSearchProgress.Taxonomy("IP", 1, 0, 1, 0)),
                List.of(new RelationSearchProgress.SourceTaxonomy("CP", 1, 0, 1)), oldBudget);
        var previous = new RelationSearchReport(2, context.requirement().textSha256(), "policy",
                List.of(new RelationSearchModel.SourceAssessment(source, List.of(), "The dependency is unclear", "Which dependency is required?")),
                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 0), 4, 4, 0,
                List.of("CALL_BUDGET"), "CALL_BUDGET", progress,
                List.of(new RelationSearchProgress.Task(source.id(), "IP", RelationSearchProgress.State.UNRESOLVED)), oldBudget);
        String signature = AnalysisCheckpointSession.digest("retained-relation-progress");
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var saved = continuation(db, mapper); var first = saved.begin(request, original.workspaceContext(), signature, tree);
            for (int call = 0; call < 4; call++) saved.beforeRelationCall(first, 4);
            saved.freezeRelationSourceCohort(first, 16);
            var priorResult = new AnalysisResult(Map.of("CP", 75, "IP", 65), tree);
            priorResult.setStatus("PARTIAL"); priorResult.setRelationSearchReport(previous);
            priorResult.setCopilot(new AnalysisCopilotResult(1, "COMPLETED", new GapAnalysisView(),
                    new PatternDetectionView(), new ArchitectureRecommendation(), List.of("Earlier assessment reports")));
            saved.complete(first, priorResult, tree);
            var stopped = saved.read(first.id(), original.workspaceContext());
            assertThat(stopped.recovery().state()).isEqualTo("STOPPED");
            request.setContinuationAction("CONTINUE"); request.setContinuationVersion(stopped.recovery().version());
            var claim = saved.begin(request, original.workspaceContext(), signature, tree);
            var policy = new RequirementRelationSearch.BudgetPolicy(8, 20, 4, true, 64, 4096, 16);
            var admitted = new RelationSearchBudget(8, 20, 0, 4, true, 0, 64, 4096, 16);
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var targets = relationTargets(context, mapper); var cp = TaxonomyShardRoot.of("CP"); var ip = TaxonomyShardRoot.of("IP");
            store.admit(context, relationCommand(request, claim, signature, policy), null,
                    Map.of(cp, targets.get(cp), ip, targets.get(ip)), targets);
            assertThat(db.sent).noneMatch(task -> task instanceof RelationAnalysisTask);
            var beforePreparation = store.snapshot(context).progress().relations();
            assertThat(beforePreparation).as("Saved relation evidence must be visible before preparation telemetry exists").isNotNull();
            assertThat(beforePreparation.assessedSources()).isEqualTo(1);
            assertThat(beforePreparation.unresolvedSearches()).isEqualTo(1);
            assertThat(beforePreparation.budget()).isEqualTo(admitted);
            assertThat(store.cancel(context)).isTrue();
            var cancelled = store.snapshot(context); assertThat(cancelled.state()).isEqualTo(ClusterAnalysisState.CANCELLED);
            assertThat(cancelled.result().getCopilot())
                    .as("A new cancelled operation cannot reuse reports from earlier relation evidence").isNull();
            var retained = cancelled.result().getRelationSearchReport();
            assertThat(retained.sources()).isEqualTo(previous.sources());
            assertThat(retained.tasks()).isEqualTo(previous.tasks());
            assertThat(retained.result()).isEqualTo(previous.result());
            assertThat(retained.budget()).isEqualTo(admitted);
            assertThat(cancelled.progress().relations().assessedSources()).isEqualTo(1);
            assertThat(cancelled.progress().relations().unresolvedSearches()).isEqualTo(1);
            assertThat(cancelled.progress().relations().budget()).isEqualTo(admitted);
            assertThat(saved.read(claim.id(), original.workspaceContext()).result().getRelationSearchReport()).isEqualTo(retained);
            // Cancellation cannot be continued. Remove only the matching live authority to exercise historical-read fencing.
            new TransactionTemplate(db.transactions).executeWithoutResult(status -> assertThat(db.em.createQuery(
                            "update AnalysisContinuationRun r set r.claimToken=:replacement where r.id=:id and r.claimToken=:token")
                    .setParameter("replacement", UUID.randomUUID().toString()).setParameter("id", claim.id())
                    .setParameter("token", claim.token()).executeUpdate()).isEqualTo(1));
            var historical = store.snapshot(context);
            assertThat(historical.progress().relations()).as("Pre-plan terminal evidence is independent of a live claim")
                    .isNotNull().isEqualTo(cancelled.progress().relations());
            assertThat(historical.result().getRelationSearchReport()).isEqualTo(retained);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anExplicitRetryBeforeCoordinatorSettlementRetainsTheOldOperationBudgetAndEvidence(boolean preparedPlan) {
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("unsettled-relation-attempt");
        var original = ClusterAnalysisStoreTest.command("requirement"); var request = relationRequest();
        var tree = relationTree(); var source = new RelationSearchModel.Node("retained-source", "CP", "Source", "", false);
        var oldBudget = new RelationSearchBudget(4, 4, 4, 4, false, 1, 16, 32, 16);
        var progress = new RelationSearchProgress(1, 1, 1, 0, 1, 0, 4, 4, 0,
                RelationSearchProgress.Step.PAUSED, null, List.of(new RelationSearchProgress.Taxonomy("IP", 1, 0, 1, 0)),
                List.of(new RelationSearchProgress.SourceTaxonomy("CP", 1, 0, 1)), oldBudget);
        var previous = new RelationSearchReport(2, context.requirement().textSha256(), "policy",
                List.of(new RelationSearchModel.SourceAssessment(source, List.of(), "An earlier dependency remains open", "Which dependency?")),
                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 0), 4, 4, 0,
                List.of("CALL_BUDGET"), "CALL_BUDGET", progress,
                List.of(new RelationSearchProgress.Task(source.id(), "IP", RelationSearchProgress.State.UNRESOLVED)), oldBudget);
        String signature = AnalysisCheckpointSession.digest("unsettled-relation-attempt");
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var saved = continuation(db, mapper); var first = saved.begin(request, original.workspaceContext(), signature, tree);
            for (int call = 0; call < 4; call++) saved.beforeRelationCall(first, 4);
            saved.freezeRelationSourceCohort(first, 16);
            var priorResult = new AnalysisResult(Map.of("CP", 75, "IP", 65), tree);
            priorResult.setStatus("PARTIAL"); priorResult.setRelationSearchReport(previous); saved.complete(first, priorResult, tree);
            var stopped = saved.read(first.id(), original.workspaceContext());
            request.setContinuationAction("CONTINUE"); request.setContinuationVersion(stopped.recovery().version());
            var claim = saved.begin(request, original.workspaceContext(), signature, tree);
            var oldPolicy = new RequirementRelationSearch.BudgetPolicy(8, 20, 4, true, 64, 4096, 16);
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var targets = relationTargets(context, mapper); var cp = TaxonomyShardRoot.of("CP"); var ip = TaxonomyShardRoot.of("IP");
            var shards = Map.of(cp, targets.get(cp), ip, targets.get(ip));
            store.admit(context, relationCommand(request, claim, signature, oldPolicy), null, shards, targets);
            for (var root : List.of(cp, ip)) store.accept(db.complete(db.task(root), ClusterAnalysisStoreTest.result(root.code(), 75)));
            var task = (RelationAnalysisTask) db.sent.getLast(); var input = store.start(task);
            if (preparedPlan) {
                saved.beforeRelationCall(claim, 20);
                var base = ClusterRelationCoordinatorTest.plan(context, task);
                var sourceNodes = new ArrayList<>(base.sourceNodes()); sourceNodes.add(source);
                var plan = new RelationSearchDistribution.Plan(base.schemaVersion(), base.preparationId(), base.originalSha256(),
                        base.sourceResultIds(), new RequirementRelationSearch.Options(base.options().limits(), 64, 16000, oldPolicy),
                        sourceNodes, base.targetRoots(), base.sources(), base.items(), base.sourceCalls(), base.durationMillis(),
                        base.warnings(), base.stopReason(), base.included());
                var prepared = ClusterRelationCoordinatorTest.completion(context, task); var preparationTask = task;
                db.completions.commit(new PreparedAnalysisCompletion<>(prepared, () -> store.persistRelationPlan(preparationTask, plan)));
                store.accept(prepared);
                task = db.sent.stream().filter(message -> message instanceof RelationAnalysisTask relation
                                && relation.taskId().relationWorkOrdinal().isPresent())
                        .map(RelationAnalysisTask.class::cast).findFirst().orElseThrow();
                input = store.start(task);
            }
            String namespace = task.taskType().name() + ":" + (task.routingRoot() == null ? "all" : task.routingRoot().code());
            var question = new AnalysisCheckpointSession.Question("R" + "a".repeat(63), signature, "MOCK", List.of("source"), "uncertain relation question");
            var owner = new AnalysisContinuationStore.QuestionOwner(context.operationId(), task.taskId().value(),
                    input.deliveryAttempt(), UUID.randomUUID().toString(), namespace);
            assertThat(saved.prepareDistributed(claim, question, owner).state()).isEqualTo("ATTEMPT");
            saved.beforeRelationCall(claim, 20);
            // A worker dies after admission. Redelivery exposes the uncertain question before the coordinator settles.
            var redelivery = store.start(task);
            var retryOwner = new AnalysisContinuationStore.QuestionOwner(context.operationId(), task.taskId().value(),
                    redelivery.deliveryAttempt(), UUID.randomUUID().toString(), namespace);
            assertThat(saved.prepareDistributed(claim, question, retryOwner).state()).isEqualTo("FAILED");
            var paused = saved.read(claim.id(), original.workspaceContext());
            assertThat(paused.recovery().state()).isEqualTo("PAUSED");
            assertThat(store.snapshot(context).state().terminal()).isFalse();
            request.setContinuationAction("RETRY"); request.setContinuationVersion(paused.recovery().version());
            request.setContinuationQuestion(paused.recovery().openQuestions().getFirst().key());
            var next = saved.begin(request, original.workspaceContext(), signature, tree);
            int spent = preparedPlan ? 6 : 5;
            var nextPolicy = new RequirementRelationSearch.BudgetPolicy(16, 40, spent, true, 128, 8192, 16);
            var nextContext = ClusterAnalysisStoreTest.context("superseding-relation-attempt");
            store.admit(nextContext, relationCommand(request, next, signature, nextPolicy), null, shards, targets);
            var superseded = store.snapshot(context);
            assertThat(superseded.state()).isEqualTo(ClusterAnalysisState.PARTIAL);
            var report = superseded.result().getRelationSearchReport();
            assertThat(report).as("Supersession must retain relation evidence before a new plan exists").isNotNull();
            assertThat(report.sources()).contains(previous.sources().getFirst());
            assertThat(report.budget().spentCalls()).isEqualTo(spent);
            assertThat(report.budget().newCalls()).isEqualTo(spent - oldPolicy.callsBeforeExecution());
            assertThat(report.budget().callsPerSlice()).isEqualTo(oldPolicy.callsPerSlice());
            assertThat(report.budget().maxTotalCalls()).isEqualTo(oldPolicy.maxTotalCalls());
            assertThat(report.budget().sourcesPerSlice()).isEqualTo(oldPolicy.sourcesPerSlice());
            assertThat(report.budget().workItemsPerSlice()).isEqualTo(oldPolicy.workItemsPerSlice());
            saved.beforeRelationCall(next, 40);
            assertThat(store.snapshot(context).result().getRelationSearchReport()).isEqualTo(report);
        }
    }

    private static AnalysisRequest relationRequest() {
        var request = new AnalysisRequest(); request.setBusinessText("requirement"); request.setProvider("MOCK");
        request.setMaxArchitectureNodes(20); request.setAnalysisScope(new AnalysisScope(Set.of("CP", "IP"), AnalysisMode.FULL));
        request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString()); return request;
    }
    private static AnalyzeRequirementCommand relationCommand(AnalysisRequest request, AnalysisContinuationStore.Claim claim,
            String signature, RequirementRelationSearch.BudgetPolicy budget) {
        return new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice",
                ClusterAnalysisStoreTest.command("requirement").workspaceContext(), null, request.getAnalysisScope())
                .withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(), signature, signature, signature,
                        budget.sourceCohortSize(), budget));
    }
    private static List<TaxonomyNodeDto> relationTree() {
        return TaxonomyShardRoot.DEFAULT_ROOTS.stream().map(TaxonomyShardRoot::code).map(root -> {
            var node = new TaxonomyNodeDto(); node.setCode(root); node.setTaxonomyRoot(root);
            node.setNameEn("Frozen " + root); node.setClassificationConfidence(1d); return node;
        }).toList();
    }
    private static Map<TaxonomyShardRoot, String> relationTargets(AnalysisOperationContext context, ObjectMapper mapper) {
        var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
        TaxonomyShardRoot.DEFAULT_ROOTS.forEach(root -> targets.put(root, mapper.writeValueAsString(
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code()))));
        return targets;
    }

    @Test void aSavedResponsesAnswerRestartsWithoutEitherEndpointAndRejectsChangedTokenBounds() throws Exception {
        String database = "jdbc:hsqldb:mem:responses-continuation-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("responses-worker");
        var original = ClusterAnalysisStoreTest.command("requirement");
        var fixture = new Scoring(mapper, "https://unused.invalid/chat/completions");
        var physical = new AtomicInteger();
        var budget = new java.util.concurrent.atomic.AtomicReference<>(new ProviderRateQuotas.TokenBudget(
                "openai.responses.gpt-4o-mini-2024-07-18.v1", 5000, 256));
        var quotas = new ProviderRateQuotas() {
            public TokenBudget requiredBudget(com.taxonomy.extension.api.llm.ProviderId provider) { return budget.get(); }
            public Decision tryReserve(com.taxonomy.extension.api.llm.ProviderId provider, String id, TokenBudget supplied, Runnable checkpoint) {
                assertThat(supplied).isEqualTo(budget.get()); checkpoint.run();
                return new Decision(new Reservation() { public String id() { return id; } public void beforeTransport() { checkpoint.run(); }
                    public void settle(LlmTransportMeter.Usage usage) { } }, 0);
            }
            public void defer(com.taxonomy.extension.api.llm.ProviderId provider, long delay) { }
        };
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            physical.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            String response = exchange.getRequestURI().getPath().endsWith("input_tokens")
                    ? "{\"object\":\"response.input_tokens\",\"input_tokens\":42}"
                    : "{\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"CP\\\":75}\"}]}],\"usage\":{\"input_tokens\":42,\"output_tokens\":15,\"total_tokens\":57}}";
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        fixture.providers.configureProviderQuotas(quotas); ReflectionTestUtils.setField(fixture.providers, "openaiApiKey", "fixture-key");
        when(fixture.gateways.getGatewayById(LlmProvider.OPENAI.id())).thenReturn(ResponsesContinuationFixture.gateway(
                "http://127.0.0.1:" + server.getAddress().getPort(), quotas, mapper));
        var root = DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP");
        var scope = new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY);
        var request = new AnalysisRequest(); request.setBusinessText("requirement"); request.setProvider("OPENAI");
        request.setMaxArchitectureNodes(20); request.setAnalysisScope(scope); request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        String signature = AnalysisCheckpointSession.digest("responses-exact-request"); SubtaxonomyAnalysisTask task;
        try {
            try (var db = new ClusterAnalysisStoreTest.Database(database, "create", sql -> sql)) {
                var saved = continuation(db, mapper);
                try (var catalogue = fixture.snapshots.bind(DurableClusterAnalysisExecutionTest.source(context), Set.of("CP"), List.of(root));
                     var provider = fixture.providers.withRequestProvider("OPENAI")) {
                    var claim = saved.begin(request, original.workspaceContext(), signature, fixture.taxonomy.getFullTree());
                    var command = new AnalyzeRequirementCommand("requirement", false, 20, "OPENAI", "alice", original.workspaceContext(), null, scope, null)
                            .withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                                    fixture.llm.recoveryPolicyFingerprint("OPENAI"), AnalysisCheckpointSession.digest("")));
                    try (var checkpoint = new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                        public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) { return saved.prepare(claim, q); }
                        public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { saved.finish(claim, q, state, detail); }
                        public void checkActive() { assertThat(saved.state(claim)).isEqualTo("RUNNING"); }
                    })) { assertThat(fixture.llm.analyzeWithBudget("requirement", scope).getRawScores()).containsEntry("CP", 75d); }
                    assertThat(physical).hasValue(2);
                    var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
                    store.admit(context, command, null, Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(root)));
                    task = db.task(TaxonomyShardRoot.of("CP"));
                }
            }
            try (var db = new ClusterAnalysisStoreTest.Database(database, "validate", sql -> sql)) {
                var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, continuation(db, mapper));
                assertThat(fixture.worker(store).score(store.start(task), task, () -> false).getRawScores()).containsEntry("CP", 75d);
                assertThat(physical).hasValue(2);
                budget.set(new ProviderRateQuotas.TokenBudget(budget.get().adapterId(), 5001, 256));
                assertThatThrownBy(() -> fixture.worker(store).score(store.start(task), task, () -> false))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Frozen continuation provider or policy changed");
                assertThat(physical).hasValue(2);
            }
        } finally { server.stop(0); }
    }
    @Test void aSavedValidatedAnswerSurvivesTheRequestScopeAndANewWorkerPersistenceFactoryWithoutNewHttp() throws Exception {
        String url = "jdbc:hsqldb:mem:cluster-continuation-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("continued-worker");
        var original = ClusterAnalysisStoreTest.command("requirement");
        var fixture = new Scoring(mapper, "https://continuation.invalid/chat/completions");
        var requests = new AtomicInteger();
        fixture.server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(fixture.url)).andRespond(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            int score = requests.incrementAndGet() == 1 ? 75 : 12;
            String code = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString().contains("IP-FROZEN-REQUEST") ? "IP" : "CP";
            String response = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", "{\"" + code + "\":" + score + "}")))));
            return withSuccess(response, org.springframework.http.MediaType.APPLICATION_JSON).createResponse(request);
        });
        var roots = List.of(DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP"),
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "IP"));
        var request = new AnalysisRequest(); request.setBusinessText("requirement"); request.setProvider("CUSTOM_OPENAI"); request.setMaxArchitectureNodes(20);
        request.setAnalysisScope(original.analysisScope()); request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        String signature = AnalysisCheckpointSession.digest("exact-fixture-request");
        SubtaxonomyAnalysisTask task;
        AnalyzeRequirementCommand command;
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            var saved = continuation(db, mapper);
            try (var catalogue = fixture.snapshots.bind(DurableClusterAnalysisExecutionTest.source(context), Set.of("CP", "IP"), roots);
                 var provider = fixture.providers.withRequestProvider("CUSTOM_OPENAI")) {
                var claim = saved.begin(request, original.workspaceContext(), signature, fixture.taxonomy.getFullTree());
                command = new AnalyzeRequirementCommand("requirement", false, 20, "CUSTOM_OPENAI", "alice", original.workspaceContext(),
                        null, original.analysisScope(), null).withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                        fixture.llm.recoveryPolicyFingerprint("CUSTOM_OPENAI"), AnalysisCheckpointSession.digest("")));
                try (var checkpoint = new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                    public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question question) { return saved.prepare(claim, question); }
                    public void finish(AnalysisCheckpointSession.Question question, String state, LlmCallDetail detail) { saved.finish(claim, question, state, detail); }
                    public void checkActive() { assertThat(saved.state(claim)).isEqualTo("RUNNING"); }
                })) {
                    assertThat(fixture.llm.analyzeWithBudget("requirement", new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY)).getRawScores()).containsEntry("CP", 75d);
                }
            }
            assertThat(AnalysisCheckpointSession.active()).isFalse();
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var wrong = command.withContinuationBinding(new AnalysisContinuationBinding(command.continuationBinding().id(),
                    command.continuationBinding().token(), AnalysisCheckpointSession.digest("changed-request"),
                    command.continuationBinding().scoringPolicyHash(), command.continuationBinding().relationPolicyHash()));
            assertThatThrownBy(() -> store.admit(context, wrong, null, Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(roots.get(0)),
                    TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(roots.get(1)))))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            store.admit(context, command, null, Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(roots.get(0)),
                    TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(roots.get(1))));
            task = db.task(TaxonomyShardRoot.of("CP"));
            var stale = store.start(task); var current = store.start(task);
            assertThatThrownBy(() -> fixture.worker(store).score(stale, task, () -> false)).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.worker(store).score(current, task, () -> false).getRawScores()).containsEntry("CP", 75d);
            assertThat(requests).hasValue(1);
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, continuation(db, mapper));
            assertThat(fixture.worker(store).score(store.start(task), task, () -> false).getRawScores()).containsEntry("CP", 75d);
            assertThat(requests).hasValue(1);
            assertThat(AnalysisCheckpointSession.active()).isFalse();
            var service = new ClusterAnalysisService(store, fixture.worker(store), new ClusterAnalysisSignals());
            // Each root runs on an independent worker scope after its predecessor settles.
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                for (var node : AnalysisTaskGraph.plan(context.operationId(), List.of(TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("IP")), false).tasks()) {
                    var actualTask = (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(context, java.time.Clock.systemUTC()).task(node);
                    var completion = executor.submit(() -> service.prepare(actualTask)).get(15, java.util.concurrent.TimeUnit.SECONDS);
                    new org.springframework.transaction.support.TransactionTemplate(db.transactions).executeWithoutResult(status -> completion.persistEffect().run());
                    store.accept(completion.completion());
                }
            }
            assertThat(store.snapshot(context).result().getRecovery().state()).isEqualTo("COMPLETED");
            assertThat(store.snapshot(context).result().getRawScores()).containsEntry("CP", 75d).containsEntry("IP", 12d);
            assertThat(requests).hasValue(2);
        }
        fixture.server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"RETRY", "SKIP"})
    void aWorkerDeathAfterDurableStartRequiresAnExplicitDecisionBeforeAnotherHttp(String decision) {
        String url = "jdbc:hsqldb:mem:cluster-uncertain-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("uncertain-worker");
        var original = ClusterAnalysisStoreTest.command("requirement");
        var fixture = new Scoring(mapper, "https://uncertain.invalid/chat/completions");
        var requests = new AtomicInteger();
        fixture.server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(fixture.url)).andRespond(http -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (requests.incrementAndGet() == 1) throw new SimulatedProcessDeath();
            return withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"CP\\\":75}\"}}]}", org.springframework.http.MediaType.APPLICATION_JSON).createResponse(http);
        });
        var roots = List.of(DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP"),
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "IP"));
        var request = new AnalysisRequest(); request.setBusinessText("requirement"); request.setProvider("CUSTOM_OPENAI"); request.setMaxArchitectureNodes(20);
        request.setAnalysisScope(original.analysisScope()); request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        String signature = AnalysisCheckpointSession.digest("exact-fixture-request");
        SubtaxonomyAnalysisTask task;
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            var saved = continuation(db, mapper); AnalyzeRequirementCommand command;
            try (var catalogue = fixture.snapshots.bind(DurableClusterAnalysisExecutionTest.source(context), Set.of("CP", "IP"), roots);
                 var provider = fixture.providers.withRequestProvider("CUSTOM_OPENAI")) {
                var claim = saved.begin(request, original.workspaceContext(), signature, fixture.taxonomy.getFullTree());
                command = new AnalyzeRequirementCommand("requirement", false, 20, "CUSTOM_OPENAI", "alice", original.workspaceContext(),
                        null, original.analysisScope(), null).withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                        fixture.llm.recoveryPolicyFingerprint("CUSTOM_OPENAI"), AnalysisCheckpointSession.digest("")));
            }
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            store.admit(context, command, null, Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(roots.get(0)),
                    TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(roots.get(1))));
            task = db.task(TaxonomyShardRoot.of("CP"));
            assertThatThrownBy(() -> fixture.worker(store).score(store.start(task), task, () -> false)).isInstanceOf(SimulatedProcessDeath.class);
            assertThat(requests).hasValue(1);
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var saved = continuation(db, mapper);
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            assertThatThrownBy(() -> fixture.worker(store).score(store.start(task), task, () -> false))
                    .isInstanceOf(AnalysisStoppedException.class);
            assertThat(requests).hasValue(1);
            var snapshot = saved.read(request.getContinuationId(), original.workspaceContext());
            assertThat(snapshot.recovery().state()).isEqualTo("PAUSED");
            assertThat(snapshot.recovery().openQuestions()).singleElement().satisfies(q -> assertThat(q.outcomeUncertain()).isTrue());
            request.setContinuationAction(decision); request.setContinuationQuestion(snapshot.recovery().openQuestions().getFirst().key());
            request.setContinuationVersion(snapshot.recovery().version() - 1);
            assertThatThrownBy(() -> saved.begin(request, original.workspaceContext(), signature, snapshot.result().getTree()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            request.setContinuationVersion(snapshot.recovery().version());
            var claim = saved.begin(request, original.workspaceContext(), signature, snapshot.result().getTree());
            AnalyzeRequirementCommand resumed;
            try (var provider = fixture.providers.withRequestProvider("CUSTOM_OPENAI")) {
                resumed = new AnalyzeRequirementCommand("requirement", false, 20, "CUSTOM_OPENAI", "alice", original.workspaceContext(),
                        null, original.analysisScope(), null).withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(), signature,
                        fixture.llm.recoveryPolicyFingerprint("CUSTOM_OPENAI"), AnalysisCheckpointSession.digest("")));
            }
            var next = ClusterAnalysisStoreTest.context("resumed-" + decision);
            store.admit(next, resumed, null, Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(roots.get(0)),
                    TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(roots.get(1))));
            var nextTask = (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(next, java.time.Clock.systemUTC())
                    .task(AnalysisTaskGraph.plan(next.operationId(), List.of(TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("IP")), false).tasks().getFirst());
            var result = fixture.worker(store).score(store.start(nextTask), nextTask, () -> false);
            assertThat(requests).hasValue("RETRY".equals(decision) ? 2 : 1);
            if ("RETRY".equals(decision)) {
                assertThat(result.getRawScores()).containsEntry("CP", 75d);
                try (var catalogue = fixture.snapshots.bind(DurableClusterAnalysisExecutionTest.source(context), Set.of("CP", "IP"), roots);
                     var provider = fixture.providers.withRequestProvider("CUSTOM_OPENAI");
                     var local = new AnalysisCheckpointSession(new AnalysisCheckpointSession.Store() {
                         public AnalysisCheckpointSession.Checkpoint prepare(AnalysisCheckpointSession.Question q) { return saved.prepare(claim, q); }
                         public void finish(AnalysisCheckpointSession.Question q, String state, LlmCallDetail detail) { saved.finish(claim, q, state, detail); }
                         public void checkActive() { assertThat(saved.state(claim)).isEqualTo("RUNNING"); }
                     })) {
                    fixture.llm.analyzeWithBudget("requirement", new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
                    assertThat(requests).hasValue(2);
                }
            }
            else assertThat(result.getRawScores()).doesNotContainKey("CP");
            // Cancellation commits the same recovery result and cannot be overwritten by late work.
            assertThat(store.cancel(next)).isTrue();
            assertThat(store.snapshot(next).result().getRecovery().state()).isEqualTo("CANCELLED");
            assertThat(saved.read(request.getContinuationId(), original.workspaceContext()).recovery().state()).isEqualTo("CANCELLED");
            assertThatThrownBy(() -> fixture.worker(store).score(store.start(nextTask), nextTask, () -> false))
                    .isInstanceOf(RuntimeException.class);
            assertThat(requests).hasValue("RETRY".equals(decision) ? 2 : 1);
        }
        fixture.server.verify();
    }
    @Test void closingTheAdmittedHttpScopeDoesNotInterruptTheDurableWorkerClaimAndScopedCancelStopsBothJournals() {
        String url = "jdbc:hsqldb:mem:cluster-detached-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
        var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("detached-worker");
        var original = ClusterAnalysisStoreTest.command("requirement"); var fixture = new Scoring(mapper, "https://detach.invalid/chat/completions");
        fixture.server.expect(requestTo(fixture.url)).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"CP\\\":75}\"}}]}", org.springframework.http.MediaType.APPLICATION_JSON));
        var roots = List.of(DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP"),
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "IP"));
        var request = new AnalysisRequest(); request.setBusinessText("requirement"); request.setProvider("CUSTOM_OPENAI");
        request.setAnalysisScope(original.analysisScope()); request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            var saved = continuation(db, mapper); var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var workspaces = mock(com.taxonomy.workspace.service.WorkspaceManager.class);
            var workspace = mock(com.taxonomy.workspace.model.UserWorkspace.class); when(workspace.getUsername()).thenReturn("alice");
            when(workspaces.getWorkspaceById("workspace")).thenReturn(workspace);
            var service = new AnalysisContinuationService(saved, fixture.taxonomy, fixture.llm, workspaces, mapper);
            ReflectionTestUtils.setField(service, "providers", fixture.providers); ReflectionTestUtils.setField(service, "clusterStore", store);
            AnalysisContinuationBinding binding;
            try (var catalogue = fixture.snapshots.bind(DurableClusterAnalysisExecutionTest.source(context), Set.of("CP", "IP"), roots);
                 var execution = service.beginDistributed(request, "alice", original.workspaceContext(), 20)) {
                assertThat(AnalysisCheckpointSession.active()).isFalse(); binding = execution.binding();
                store.admit(context, execution.bind(original), null, Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(roots.get(0)),
                        TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(roots.get(1))));
            }
            var claim = new AnalysisContinuationStore.Claim(binding.id(), binding.token(), null);
            assertThat(saved.state(claim)).isEqualTo("RUNNING");
            var task = db.task(TaxonomyShardRoot.of("CP"));
            assertThat(fixture.worker(store).score(store.start(task), task, () -> false).getRawScores()).containsEntry("CP", 75d);
            var cancelled = service.cancel(binding.id(), "alice", original.workspaceContext());
            assertThat(cancelled.recovery().state()).isEqualTo("CANCELLED");
            assertThat(store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.CANCELLED);
            assertThat(store.snapshot(context).result().getRawScores()).containsEntry("CP", 75d);
            assertThat(AnalysisCheckpointSession.active()).isFalse();
        }
        fixture.server.verify();
    }

    @Test void simultaneousAdmissionsCannotSkipAnInterveningOperationBinding() throws Exception {
        String url = "jdbc:hsqldb:mem:cluster-admission-race-" + UUID.randomUUID() + ";hsqldb.tx=mvcc";
        var mapper = new ObjectMapper(); var original = ClusterAnalysisStoreTest.command("requirement");
        var context = ClusterAnalysisStoreTest.context("first-bound-operation"); var fixture = new Scoring(mapper, "https://unused.invalid/chat/completions");
        var roots = List.of(DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP"),
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "IP"));
        var request = new AnalysisRequest(); request.setBusinessText("requirement"); request.setProvider("CUSTOM_OPENAI"); request.setMaxArchitectureNodes(20);
        request.setAnalysisScope(original.analysisScope()); request.setResumable(true); request.setContinuationId(UUID.randomUUID().toString());
        try (var db = new ClusterAnalysisStoreTest.Database(url, "create", sql -> sql)) {
            var raw = new InterleavingContinuationStore(db.em, mapper);
            var proxy = new ProxyFactory(raw); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(db.transactions, new AnnotationTransactionAttributeSource()));
            var saved = (AnalysisContinuationStore) proxy.getProxy(); AnalyzeRequirementCommand command;
            try (var catalogue = fixture.snapshots.bind(DurableClusterAnalysisExecutionTest.source(context), Set.of("CP", "IP"), roots);
                 var provider = fixture.providers.withRequestProvider("CUSTOM_OPENAI")) {
                String hash = AnalysisCheckpointSession.digest("admission-race");
                var claim = saved.begin(request, original.workspaceContext(), hash, fixture.taxonomy.getFullTree());
                command = new AnalyzeRequirementCommand("requirement", false, 20, "CUSTOM_OPENAI", "alice", original.workspaceContext(),
                        null, original.analysisScope(), null).withContinuationBinding(new AnalysisContinuationBinding(claim.id(), claim.token(), hash,
                        fixture.llm.recoveryPolicyFingerprint("CUSTOM_OPENAI"), AnalysisCheckpointSession.digest("")));
            }
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, saved);
            var inputs = Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(roots.get(0)), TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(roots.get(1)));
            store.admit(context, command, null, inputs); raw.synchronizeReads = true;
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> admit(store, ClusterAnalysisStoreTest.context("competing-one"), command, inputs));
                var second = executor.submit(() -> admit(store, ClusterAnalysisStoreTest.context("competing-two"), command, inputs));
                assertThat(List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS), second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }
            raw.synchronizeReads = false;
            String winner = saved.boundOperation(request.getContinuationId());
            assertThat(winner).isIn("competing-one", "competing-two");
            var claim = new AnalysisContinuationStore.Claim(command.continuationBinding().id(), command.continuationBinding().token(), null);
            var foreign = new AnalysisContinuationStore.QuestionOwner(context.operationId(), "foreign-task", 1, UUID.randomUUID().toString(), "root:CP");
            assertThatThrownBy(() -> saved.prepareDistributed(claim,
                    new AnalysisCheckpointSession.Question("a".repeat(64), "b".repeat(64), "CUSTOM_OPENAI", List.of("CP"), "original prompt"), foreign))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            var offered = new AnalysisCheckpointSession.Question("a".repeat(64), "b".repeat(64), "CUSTOM_OPENAI", List.of("CP"), "original prompt");
            var currentOwner = new AnalysisContinuationStore.QuestionOwner(winner, "current-task", 1, UUID.randomUUID().toString(), "root:CP");
            var admitted = saved.prepareDistributed(claim, offered, currentOwner);
            var acceptedKey = admitted.question();
            var otherWorker = new AnalysisContinuationStore.QuestionOwner(winner, "current-task", 1, UUID.randomUUID().toString(), "root:CP");
            assertThatThrownBy(() -> saved.finishDistributed(claim, acceptedKey, "SUCCESS", null, otherWorker))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThatThrownBy(() -> saved.finishDistributed(claim, acceptedKey, "SUCCESS", null, foreign))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(saved.read(claim.id(), original.workspaceContext()).recovery().completedCalls()).isZero();
            var detail = new LlmCallDetail(); detail.setScores(Map.of("CP", 75));
            saved.finishDistributed(claim, acceptedKey, "SUCCESS", detail, currentOwner);
            assertThat(saved.read(claim.id(), original.workspaceContext()).recovery().completedCalls()).isEqualTo(1);
        }
    }
    private static boolean admit(ClusterAnalysisStore store, AnalysisOperationContext context, AnalyzeRequirementCommand command,
                                 Map<TaxonomyShardRoot,String> inputs) {
        try { store.admit(context, command, null, inputs); return true; }
        catch (org.springframework.web.server.ResponseStatusException rejected) { return false; }
    }
    public static class InterleavingContinuationStore extends AnalysisContinuationStore {
        final java.util.concurrent.CountDownLatch bothRead = new java.util.concurrent.CountDownLatch(2);
        volatile boolean synchronizeReads;
        InterleavingContinuationStore(jakarta.persistence.EntityManager em, ObjectMapper mapper) { super(em, mapper); }
        @Override @org.springframework.transaction.annotation.Transactional(readOnly = true)
        public String boundOperation(String id) {
            String previous = super.boundOperation(id);
            if (synchronizeReads) {
                bothRead.countDown();
                try { if (!bothRead.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Both durable admission reads must interleave"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }
            return previous;
        }
    }

    private static final class SimulatedProcessDeath extends Error { }

    private static AnalysisContinuationStore continuation(ClusterAnalysisStoreTest.Database db, ObjectMapper mapper) {
        var factory = new ProxyFactory(new AnalysisContinuationStore(db.em, mapper)); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(db.transactions, new AnnotationTransactionAttributeSource()));
        return (AnalysisContinuationStore) factory.getProxy();
    }

    private static final class Scoring {
        final String url;
        final CatalogueSnapshotService snapshots;
        final TaxonomyService taxonomy;
        final LlmProviderConfig providers;
        final LlmService llm;
        final MockRestServiceServer server;
        final ObjectMapper mapper;
        final LlmGatewayRegistry gateways;
        Scoring(ObjectMapper mapper, String url) {
            this.mapper = mapper; this.url = url;
            var nodes = mock(TaxonomyNodeRepository.class); var embedding = mock(LocalEmbeddingService.class);
            var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
            snapshots = new CatalogueSnapshotService(nodes, overlay, new CatalogueRuntimePolicy("worker", "CP,IP"), mock(CatalogueSourceJournal.class));
            taxonomy = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            providers = new LlmProviderConfig(embedding);
            ReflectionTestUtils.setField(providers, "customLlmUrl", url); ReflectionTestUtils.setField(providers, "customLlmModel", "fixture");
            var http = new RestTemplate(); server = MockRestServiceServer.bindTo(http).build();
            AnalysisRuntimeSettings settings = (key, fallback) -> "llm.retry.max".equals(key) ? 0 : fallback;
            var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, url, "fixture", 0, http, mapper, new LlmResponseParser(mapper), settings, null, null);
            gateways = mock(LlmGatewayRegistry.class); when(gateways.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(gateway);
            var templates = mock(PromptTemplateService.class);
            when(templates.renderPrompt(anyString(), anyString(), anyString(), anyDouble(), anyString())).thenReturn("FROZEN-REQUEST");
            when(templates.renderRootPrompt(anyString(), anyString(), anyString())).thenAnswer(i -> i.<String>getArgument(0) + "-FROZEN-REQUEST");
            llm = new LlmService(providers, gateways, mapper, taxonomy, templates, embedding, mock(SavedAnalysisService.class));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
        }
        FrozenClusterAnalysisComputation worker(ClusterAnalysisStore store) {
            return new FrozenClusterAnalysisComputation(store, snapshots, llm, providers,
                    () -> new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 16 * 1024 * 1024, 5000, 1800000),
                            () -> new AnalysisMemoryGuard.Sample(1, 1024L * 1024 * 1024), System::currentTimeMillis), mapper);
        }
    }
}
