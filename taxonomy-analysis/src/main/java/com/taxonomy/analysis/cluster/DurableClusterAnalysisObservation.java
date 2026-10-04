package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisEventPublisher;
import com.taxonomy.analysis.dag.AnalysisMessageFactory;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Durable authority behind web-pod observation. Live hints coalesce into reads of
 * the latest persisted snapshot; reconnect never invents historical full views
 * from the smaller durable progress events. No timer polls operation state.
 */
public final class DurableClusterAnalysisObservation implements ClusterAnalysisObservation, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DurableClusterAnalysisObservation.class);
    private static final int MAX_PREVIEW_SCORES = 8192;
    private final ClusterAnalysisStore store;
    private final ClusterAnalysisSignals signals;
    private final AnalysisEventPublisher publisher;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Set<Observation> observations = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public DurableClusterAnalysisObservation(ClusterAnalysisStore store, ClusterAnalysisSignals signals,
                                            AnalysisEventPublisher publisher) {
        this(store, signals, publisher, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** Supports a deployment-provided executor with an independently managed lifecycle. */
    public DurableClusterAnalysisObservation(ClusterAnalysisStore store, ClusterAnalysisSignals signals,
                                            AnalysisEventPublisher publisher, Executor executor) {
        this(store, signals, publisher, executor, false);
    }

    private DurableClusterAnalysisObservation(ClusterAnalysisStore store, ClusterAnalysisSignals signals,
                                             AnalysisEventPublisher publisher, Executor executor, boolean owned) {
        this.store = Objects.requireNonNull(store);
        this.signals = Objects.requireNonNull(signals);
        this.publisher = Objects.requireNonNull(publisher);
        this.executor = Objects.requireNonNull(executor);
        this.ownedExecutor = owned ? (ExecutorService) executor : null;
    }

    @Override public Optional<ClusterAnalysisView> snapshot(String operationId, String owner, WorkspaceContext scope) {
        return find(operationId, owner, scope).map(context -> view(context, store.snapshot(context)));
    }

    @Override public Optional<ClusterAnalysisView> cancel(String operationId, String owner, WorkspaceContext scope) {
        return find(operationId, owner, scope).map(context -> {
            if (store.cancel(context)) {
                var event = new AnalysisMessageFactory(context, Clock.systemUTC()).cancellation("CANCELLED");
                signals.cancellation(event);
                try { publisher.cancellation(event); }
                catch (RuntimeException unavailable) {
                    // Durable cancellation remains authoritative before queued/redelivered work
                    // and before late results. A broker outage must not undo accepted cancellation.
                    log.warn("Cluster cancellation committed; live control delivery unavailable for operation {}", operationId);
                }
            }
            return view(context, store.snapshot(context));
        });
    }

    @Override public List<ClusterAnalysisView> recent(String owner, WorkspaceContext scope,
                                                     Long projectId, Long requirementId) {
        return store.recent(owner, scope, projectId, requirementId).stream()
                .map(context -> view(context, store.snapshot(context))).toList();
    }

    @Override public AnalysisResult result(String operationId, String owner, WorkspaceContext scope) {
        var context = find(operationId, owner, scope).orElseThrow(DurableClusterAnalysisObservation::notFound);
        var durable = store.snapshot(context);
        if (!durable.state().terminal() || durable.result() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis result is not ready");
        }
        return durable.result();
    }

    @Override public RecoveryInput request(String operationId, String owner, WorkspaceContext scope) {
        var context = find(operationId, owner, scope).orElseThrow(DurableClusterAnalysisObservation::notFound);
        var command = store.command(context);
        var authority = context.authority();
        return new RecoveryInput(operationId, command.businessText(), command.provider(), command.analysisScope(),
                new ClusterAnalysisView.ObservationScope(authority.workspaceId(), authority.repositoryId(),
                        authority.branch(), authority.sourceCommit()));
    }

    @Override public synchronized SseEmitter events(String operationId, String owner, WorkspaceContext scope,
                                                   long afterSequence) {
        if (afterSequence < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid replay cursor");
        var context = find(operationId, owner, scope).orElseThrow(DurableClusterAnalysisObservation::notFound);
        if (closed) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Observation is stopping");
        var observation = new Observation(context, owner, scope, afterSequence);
        observations.add(observation);
        observation.attach();
        return observation.emitter;
    }

    private Optional<AnalysisOperationContext> find(String operationId, String owner, WorkspaceContext scope) {
        try { return store.findAuthorized(operationId, owner, scope); }
        catch (SecurityException hidden) { throw notFound(); }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var observation : List.copyOf(observations)) observation.finish(null);
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    private final class Observation {
        private final AnalysisOperationContext context;
        private final String owner;
        private final WorkspaceContext scope;
        private final SseEmitter emitter = new SseEmitter(0L);
        private ClusterAnalysisSignals.Subscription subscription;
        private long sequence;
        private boolean reading, requested, complete, sent;

        private Observation(AnalysisOperationContext context, String owner, WorkspaceContext scope, long sequence) {
            this.context = context; this.owner = owner; this.scope = scope; this.sequence = sequence;
        }

        private synchronized void attach() {
            emitter.onCompletion(() -> finish(null));
            emitter.onTimeout(() -> finish(null));
            emitter.onError(this::finish);
            // Subscription precedes the first read. Concurrent wakeups coalesce and cannot
            // fall between snapshot retrieval and listener registration.
            subscription = signals.listen(context, event -> requestRead());
            subscription.onReconnect(this::requestRead);
            requestRead();
        }

        private synchronized void requestRead() {
            if (complete) return;
            requested = true;
            if (reading) return;
            reading = true;
            try { executor.execute(this::drain); }
            catch (RuntimeException stopped) { finish(stopped); }
        }

        private void drain() {
            while (true) {
                synchronized (this) {
                    if (complete || !requested) { reading = false; return; }
                    requested = false;
                }
                try {
                    // Runs outside a producer's after-commit callback/transaction context.
                    var authorized = find(context.operationId(), owner, scope)
                            .orElseThrow(DurableClusterAnalysisObservation::notFound);
                    if (!context.equals(authorized)) throw notFound();
                    var durable = store.snapshot(context);
                    synchronized (this) {
                        if (complete) return;
                        if (durable.revision() > sequence) {
                            emitter.send(SseEmitter.event().id(Long.toString(durable.revision()))
                                    .name(sent ? "progress" : "snapshot").data(view(context, durable)));
                            sequence = durable.revision();
                            sent = true;
                        }
                        if (durable.state().terminal()) { finish(null); return; }
                    }
                } catch (IOException | RuntimeException failure) {
                    finish(failure);
                    return;
                }
            }
        }

        private synchronized void finish(Throwable failure) {
            if (complete) return;
            complete = true;
            if (subscription != null) subscription.close();
            observations.remove(this);
            if (failure == null) emitter.complete();
            else emitter.completeWithError(failure);
        }
    }

    private enum TaskState { QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, STOPPED }
    private record TaskKey(AnalysisTaskType type, TaxonomyShardRoot root) { }
    private static final class TaskCounts {
        private int queued, running, completed, failed;
        private void include(TaskState state) {
            switch (state) {
                case QUEUED -> queued++;
                case RUNNING -> running++;
                case COMPLETED -> completed++;
                case PARTIAL, FAILED -> failed++;
                case STOPPED -> { /* Cancellation is explicit in the operation status. */ }
            }
        }
    }

    private static ClusterAnalysisView view(AnalysisOperationContext context, ClusterAnalysisStore.Snapshot durable) {
        var counts = new LinkedHashMap<TaskKey, TaskCounts>();
        for (var task : durable.tasks()) {
            var key = new TaskKey(AnalysisTaskType.valueOf(task.type()), task.root() == null ? null : TaxonomyShardRoot.of(task.root()));
            counts.computeIfAbsent(key, ignored -> new TaskCounts()).include(TaskState.valueOf(task.state()));
        }
        var tasks = counts.entrySet().stream().map(entry -> new ClusterAnalysisView.TaskCounts(entry.getKey().type(),
                entry.getKey().root(), entry.getValue().queued, entry.getValue().running,
                entry.getValue().completed, entry.getValue().failed)).toList();
        Map<String, Integer> raw = durable.result() == null ? Map.of() : durable.result().getRawScores();
        var scores = new LinkedHashMap<String, Integer>();
        raw.entrySet().stream().limit(MAX_PREVIEW_SCORES).forEach(entry -> scores.put(entry.getKey(), entry.getValue()));
        var authority = context.authority();
        boolean terminal = durable.state().terminal();
        long now = System.currentTimeMillis(), end = terminal ? durable.updatedAt() : now;
        Long executionStart = durable.tasks().stream().map(ClusterAnalysisStore.TaskView::startedAt)
                .filter(Objects::nonNull).min(Long::compare).orElse(null);
        String status = durable.state() == ClusterAnalysisState.RELATIONS || durable.state() == ClusterAnalysisState.FINALIZING
                ? "RUNNING" : durable.state().name();
        String phase = switch (durable.state()) {
            case QUEUED -> "QUEUED";
            case RUNNING -> "SCORING";
            case RELATIONS -> "RELATIONS";
            case FINALIZING -> "FINALIZING";
            case COMPLETED, PARTIAL, CANCELLED -> "FINISHED";
        };
        var requirement = context.requirement();
        var provenance = requirement.projectId() == null && requirement.requirementId() == null && requirement.snapshotId() == null
                ? null : new AnalysisProvenance(requirement.projectId(), requirement.requirementId(), requirement.snapshotId(), null);
        var snapshot = new AnalysisProgressRegistry.Snapshot(context.operationId(), status, phase, null,
                durable.state() == ClusterAnalysisState.CANCELLED ? "CANCELLED" : null,
                durable.revision(), durable.createdAt(), durable.updatedAt(), now, raw.size(), raw.size() > scores.size(),
                Map.copyOf(scores), List.of(), 0, null, "EXTERNAL_OR_UNKNOWN", "EXTERNAL_OR_UNKNOWN", provenance,
                terminal ? durable.updatedAt() : null, Math.max(0, end - durable.createdAt()), executionStart,
                Math.max(0, (executionStart == null ? end : executionStart) - durable.createdAt()),
                executionStart == null ? 0 : Math.max(0, end - executionStart), null, null);
        return new ClusterAnalysisView(snapshot, ClusterAnalysisView.Transport.ARTEMIS,
                new ClusterAnalysisView.ObservationScope(authority.workspaceId(), authority.repositoryId(),
                        authority.branch(), authority.sourceCommit()),
                new ClusterAnalysisView.TaskSummary(durable.completedRoots(), durable.totalRoots(), tasks));
    }
}
