package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.controller.AnalysisProgressController;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.taxonomy.analysis.cluster.ClusterAnalysisStoreTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DurableClusterAnalysisObservationTest {
    private static final TaxonomyShardRoot CP = TaxonomyShardRoot.of("CP"), IP = TaxonomyShardRoot.of("IP");
    private static final WorkspaceContext SCOPE = command("requirement").workspaceContext();

    @Test void stagedAssessmentStaysRunningAndCannotBeRecoveredUntilFinalizationCommits() {
        try (var db = new Database(); var observer = new DurableClusterAnalysisObservation(
                db.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE)) {
            var context = context("finalizing-observe"); var original = command("requirement");
            var command = new AnalyzeRequirementCommand(original.businessText(), true, original.maxArchitectureNodes(),
                    original.provider(), original.username(), original.workspaceContext(), null, original.analysisScope());
            var targets = new LinkedHashMap<TaxonomyShardRoot, String>();
            TaxonomyShardRoot.DEFAULT_ROOTS.forEach(root -> targets.put(root, "{}"));
            db.store.admit(context, command, null, Map.of(CP, "{}", IP, "{}"), targets);
            for (var root : List.of(CP, IP)) db.store.accept(db.complete(db.task(root), result(root.code(), 70)));
            var pending = observer.snapshot(context.operationId(), "alice", SCOPE).orElseThrow();
            assertEquals("RUNNING", pending.snapshot().status());
            assertEquals("FINALIZING", pending.snapshot().phase());
            assertNull(pending.snapshot().finishedAt());
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> observer.result(context.operationId(), "alice", SCOPE)).getStatusCode());
            db.store.finalizeResult(context, db.store.snapshot(context).result());
            assertEquals("SUCCESS", observer.result(context.operationId(), "alice", SCOPE).getStatus());
            assertEquals("COMPLETED", observer.snapshot(context.operationId(), "alice", SCOPE).orElseThrow().snapshot().status());
        }
    }

    @Test void durableViewExposesCountsAndExactAuthorityWhileHiddenRunsStayNotFound() {
        try (var db = new Database(); var observer = new DurableClusterAnalysisObservation(
                db.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE)) {
            var context = context("observe"); db.admit(context); db.store.start(db.task(CP));
            var view = observer.snapshot("observe", "alice", SCOPE).orElseThrow();
            assertEquals("RUNNING", view.snapshot().status());
            assertEquals(2, view.cluster().totalRoots());
            assertEquals(0, view.cluster().completedRoots());
            assertEquals(List.of(new ClusterAnalysisView.TaskCounts(AnalysisTaskType.SUBTAXONOMY_ANALYSIS, CP, 0, 1, 0, 0),
                    new ClusterAnalysisView.TaskCounts(AnalysisTaskType.SUBTAXONOMY_ANALYSIS, IP, 1, 0, 0, 0)), view.cluster().tasks());
            assertEquals(new ClusterAnalysisView.ObservationScope("workspace", "repo", "draft", "source"), view.scope());
            var input = observer.request("observe", "alice", SCOPE);
            assertEquals("requirement", input.businessText());
            assertEquals("observe", input.operationId());
            assertEquals(view.scope(), input.scope());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                    () -> observer.request("observe", "bob", SCOPE)).getStatusCode());
            assertTrue(observer.snapshot("missing", "alice", SCOPE).isEmpty());
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                    () -> observer.snapshot("observe", "bob", SCOPE)).getStatusCode());
            var foreign = new WorkspaceContext("alice", "workspace", "other", "repo");
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                    () -> observer.events("observe", "alice", foreign, 0)).getStatusCode());
            assertEquals(List.of("observe"), observer.recent("alice", SCOPE, null, null).stream()
                    .map(value -> value.snapshot().operationId()).toList());
            assertTrue(observer.recent("bob", SCOPE, null, null).isEmpty());
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> observer.result("observe", "alice", SCOPE)).getStatusCode());
        }
    }

    @Test void cancellationCommitsBeforeControlPublicationAndLateWorkCannotReplaceRecoveredResult() {
        try (var db = new Database()) {
            var context = context("cancel-observe"); db.admit(context); db.store.start(db.task(CP));
            var signals = new ClusterAnalysisSignals();
            var published = new AtomicInteger();
            AnalysisEventPublisher publisher = new AnalysisEventPublisher() {
                @Override public void progress(AnalysisProgressEvent ignored) { }
                @Override public void cancellation(AnalysisCancellationEvent event) {
                    assertEquals(ClusterAnalysisState.CANCELLED, db.store.snapshot(context).state());
                    assertEquals(context, ClusterAnalysisStore.context(event.envelope()));
                    published.incrementAndGet();
                    throw new IllegalStateException("broker disconnected after durable cancellation");
                }
            };
            try (var worker = signals.worker(context);
                 var observer = new DurableClusterAnalysisObservation(db.store, signals, publisher)) {
                var cancelled = observer.cancel("cancel-observe", "alice", SCOPE).orElseThrow();
                assertEquals("CANCELLED", cancelled.snapshot().status());
                assertTrue(worker.cancelled());
                assertEquals(1, published.get());
                observer.cancel("cancel-observe", "alice", SCOPE);
                assertEquals(1, published.get());
                assertFalse(db.store.accept(db.complete(db.task(CP), result("CP", 90))));
                var recovered = observer.result("cancel-observe", "alice", SCOPE);
                assertEquals("PARTIAL", recovered.getStatus());
                assertTrue(recovered.getRawScores().isEmpty());
                assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                        () -> observer.cancel("cancel-observe", "bob", SCOPE)).getStatusCode());
            }
        }
    }

    @Test void sseSubscribesBeforeReadCoalescesEventsAndResumesFromDurableStateOnAnotherPod() throws Exception {
        try (var db = new Database()) {
            var context = context("sse-observe"); db.admit(context);
            var firstSignals = new ClusterAnalysisSignals();
            var executor = new PendingExecutor();
            try (var observer = new DurableClusterAnalysisObservation(db.store, firstSignals, AnalysisEventPublisher.NONE, executor)) {
                var first = connect(observer, "sse-observe", 0);
                assertEquals(1, firstSignals.listenerCount());
                // A worker finishes between registration and the first durable read.
                db.store.start(db.task(CP)); db.store.accept(db.complete(db.task(CP), result("CP", 50)));
                var revision = db.store.snapshot(context).revision();
                var events = db.store.events(context, 0, 100);
                for (var event : events.reversed()) firstSignals.progress(event);
                assertEquals(1, executor.pending.size(), "Concurrent hints coalesce into one queued reconciliation");
                executor.drain();
                String initial = first.getResponse().getContentAsString();
                assertTrue(initial.contains("id:" + revision));
                assertTrue(initial.contains("\"completedRoots\":1"));
                assertTrue(executor.pending.isEmpty(), "Normal observation must not schedule database polling");
                firstSignals.progress(events.getLast());
                firstSignals.progress(events.getFirst());
                var foreign = new AnalysisOperationContext(context.operationId(),
                        new AnalysisSourceAuthority("foreign", "workspace", "draft", "source"),
                        context.requirement(), context.correlationId());
                firstSignals.progress(new AnalysisMessageFactory(foreign, Clock.systemUTC())
                        .progress(revision + 10, AnalysisProgressPhase.TASK_COMPLETED, null, 2, 2));
                executor.drain();
                assertEquals(initial, first.getResponse().getContentAsString());
                observer.close();
                assertEquals(0, firstSignals.listenerCount());
                db.store.start(db.task(IP)); db.store.accept(db.complete(db.task(IP), result("IP", 70)));
                var secondSignals = new ClusterAnalysisSignals();
                try (var resumed = new DurableClusterAnalysisObservation(db.store, secondSignals, AnalysisEventPublisher.NONE, executor)) {
                    var second = connect(resumed, "sse-observe", revision);
                    executor.drain();
                    var replay = second.getResponse().getContentAsString();
                    assertTrue(replay.contains("id:" + db.store.snapshot(context).revision()));
                    assertTrue(replay.contains("\"status\":\"COMPLETED\""));
                    assertEquals(0, secondSignals.listenerCount(), "Terminal observation releases its subscription");
                    assertEquals(70, resumed.result("sse-observe", "alice", SCOPE).getRawScores().get("IP"));
                    assertEquals(2, db.sent.size(), "Reconnect never submits a new analysis or task");
                }
            }
        }
    }

    @Test void brokerReconnectRepairsMissedWakeWithoutRepeatedOrHistoricalInventedSnapshots() throws Exception {
        try (var db = new Database()) {
            var context = context("reconnect-observe"); db.admit(context);
            var signals = new ClusterAnalysisSignals(); var executor = new PendingExecutor();
            try (var observer = new DurableClusterAnalysisObservation(db.store, signals, AnalysisEventPublisher.NONE, executor)) {
                var request = connect(observer, "reconnect-observe", 0); executor.drain();
                String first = request.getResponse().getContentAsString();
                db.store.start(db.task(CP));
                assertTrue(executor.pending.isEmpty());
                signals.reconnected(); executor.drain();
                String second = request.getResponse().getContentAsString();
                assertTrue(second.length() > first.length());
                assertTrue(second.contains("id:" + db.store.snapshot(context).revision()));
                signals.reconnected(); executor.drain();
                assertEquals(second, request.getResponse().getContentAsString());
            }
        }
    }

    @Test void productionExecutorReadsCommittedCompletionAndReleasesTheLiveSubscription() throws Exception {
        try (var db = new Database()) {
            var context = context("async-observe"); db.admit(context);
            var signals = new ClusterAnalysisSignals();
            db.store.eventPublisher(signals::progress);
            try (var observer = new DurableClusterAnalysisObservation(db.store, signals, AnalysisEventPublisher.NONE)) {
                var request = connect(observer, "async-observe", 0);
                for (var root : List.of(CP, IP)) {
                    db.store.start(db.task(root));
                    db.store.accept(db.complete(db.task(root), result(root.code(), 70)));
                }
                request.getAsyncResult(5000);
                assertTrue(request.getResponse().getContentAsString().contains("\"status\":\"COMPLETED\""));
                assertEquals(0, signals.listenerCount());
            }
        }
    }

    private static MvcResult connect(DurableClusterAnalysisObservation observer, String id, long after) throws Exception {
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(SCOPE);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnalysisProgressController(
                new AnalysisProgressRegistry(new StandardEnvironment()), resolver, observer)).build();
        return mvc.perform(get("/api/analysis-runs/" + id + "/events").param("afterSequence", Long.toString(after)))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
    }

    private static final class PendingExecutor implements Executor {
        private final Queue<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable command) { pending.add(command); }
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    }
}
