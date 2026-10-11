package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.*;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
                    assertThat(fixture.llm.analyzeWithBudget("requirement", new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY)).getRawScores()).containsEntry("CP", 75);
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
            assertThat(fixture.worker(store).score(current, task, () -> false).getRawScores()).containsEntry("CP", 75);
            assertThat(requests).hasValue(1);
        }
        try (var db = new ClusterAnalysisStoreTest.Database(url, "validate", sql -> sql)) {
            var store = new ClusterAnalysisStore(db.em, db.transactions, mapper, db.dispatch, continuation(db, mapper));
            assertThat(fixture.worker(store).score(store.start(task), task, () -> false).getRawScores()).containsEntry("CP", 75);
            assertThat(requests).hasValue(1);
            assertThat(AnalysisCheckpointSession.active()).isFalse();
            var service = new ClusterAnalysisService(store, fixture.worker(store), new ClusterAnalysisSignals());
            // Independent root workers share only durable authority; both scopes execute concurrently.
            var prepared = new java.util.ArrayList<java.util.concurrent.Future<PreparedAnalysisCompletion<SubtaxonomyAnalysisCompleted>>>();
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var ready = new java.util.concurrent.CountDownLatch(2);
                for (var node : AnalysisTaskGraph.plan(context.operationId(), List.of(TaxonomyShardRoot.of("CP"), TaxonomyShardRoot.of("IP")), false).tasks()) {
                    var actualTask = (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(context, java.time.Clock.systemUTC()).task(node);
                    prepared.add(executor.submit(() -> {
                        ready.countDown(); assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        return service.prepare(actualTask);
                    }));
                }
                for (var pending : prepared) {
                    var completion = pending.get(15, java.util.concurrent.TimeUnit.SECONDS);
                    new org.springframework.transaction.support.TransactionTemplate(db.transactions).executeWithoutResult(status -> completion.persistEffect().run());
                    store.accept(completion.completion());
                }
            }
            assertThat(store.snapshot(context).result().getRecovery().state()).isEqualTo("COMPLETED");
            assertThat(store.snapshot(context).result().getRawScores()).containsEntry("CP", 75).containsEntry("IP", 12);
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
                assertThat(result.getRawScores()).containsEntry("CP", 75);
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
            assertThat(fixture.worker(store).score(store.start(task), task, () -> false).getRawScores()).containsEntry("CP", 75);
            var cancelled = service.cancel(binding.id(), "alice", original.workspaceContext());
            assertThat(cancelled.recovery().state()).isEqualTo("CANCELLED");
            assertThat(store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.CANCELLED);
            assertThat(store.snapshot(context).result().getRawScores()).containsEntry("CP", 75);
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
            var gateways = mock(LlmGatewayRegistry.class); when(gateways.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(gateway);
            var templates = mock(PromptTemplateService.class);
            when(templates.renderPrompt(anyString(), anyString(), anyString(), anyInt(), anyString())).thenReturn("FROZEN-REQUEST");
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
