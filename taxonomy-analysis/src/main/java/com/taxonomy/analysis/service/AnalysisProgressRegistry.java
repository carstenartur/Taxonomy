package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded live telemetry, not a replacement for durable portfolio jobs or semantic history. */
@Component
public class AnalysisProgressRegistry {
    static final int MAX_ACTIVE = 4, MAX_RETAINED = 16, MAX_CALLS = 32, MAX_SCORES = 8192, MAX_TEXT = 8192;
    private static final long RETENTION_MILLIS = 10 * 60 * 1000;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final AnalysisAdmissionQueue admission;
    private final long maximumQueueWaitMillis;
    private final AnalysisMemoryGuard.Policy policy;
    private final String databaseStorage;
    private final String indexStorage;

    public AnalysisProgressRegistry(Environment environment) {
        int running = environment.getProperty("taxonomy.analysis.max-concurrent-jobs", Integer.class, MAX_ACTIVE);
        int queued = environment.getProperty("taxonomy.analysis.queue-capacity", Integer.class, 16);
        admission = new AnalysisAdmissionQueue(new AnalysisAdmissionQueue.Limits(running, queued,
                environment.getProperty("taxonomy.analysis.max-concurrent-jobs-per-user", Integer.class, Math.min(2, running)),
                environment.getProperty("taxonomy.analysis.queue-capacity-per-user", Integer.class, Math.min(8, queued))));
        long queueSeconds = environment.getProperty("taxonomy.analysis.maximum-queue-wait-seconds", Long.class, 1800L);
        if (queueSeconds < 1 || queueSeconds > 86_400) {
            throw new IllegalArgumentException("taxonomy.analysis.maximum-queue-wait-seconds must be between 1 and 86400");
        }
        maximumQueueWaitMillis = queueSeconds * 1000L;
        policy = new AnalysisMemoryGuard.Policy(
                environment.getProperty("taxonomy.analysis.runtime.warning-percent", Integer.class, 80),
                environment.getProperty("taxonomy.analysis.runtime.stop-percent", Integer.class, 92),
                environment.getProperty("taxonomy.analysis.runtime.minimum-headroom-mb", Long.class, 16L) * 1024 * 1024,
                environment.getProperty("taxonomy.analysis.runtime.pressure-seconds", Long.class, 5L) * 1000,
                environment.getProperty("taxonomy.analysis.runtime.maximum-duration-seconds", Long.class, 1800L) * 1000);
        String url = environment.getProperty("spring.datasource.url", "");
        databaseStorage = url.startsWith("jdbc:hsqldb:mem:") ? "IN_MEMORY"
                : url.startsWith("jdbc:hsqldb:file:") ? "FILE" : "EXTERNAL_OR_UNKNOWN";
        indexStorage = "local-heap".equals(environment.getProperty(
                "spring.jpa.properties.hibernate.search.backend.directory.type", "local-heap"))
                ? "IN_MEMORY" : "FILESYSTEM_OR_EXTERNAL";
    }

    private record Scope(String owner, String workspace, String branch, String repository) {
        static Scope of(String owner, WorkspaceContext context) {
            if (owner == null || owner.isBlank() || context == null
                    || context.currentBranch() == null || context.currentBranch().isBlank()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found");
            }
            return new Scope(owner, context.workspaceId(), context.currentBranch(), context.repositoryId());
        }
    }

    public record CallView(long id, String provider, String node, String status, long startedAt, long durationMillis) { }
    public record CallDetail(String prompt, String response, boolean truncated, String error,
                             int promptLength, int responseLength) {
        public CallDetail(String prompt, String response, boolean truncated) {
            this(prompt, response, truncated, "", length(prompt), length(response));
        }
    }
    public record Snapshot(String operationId, String status, String phase, String node, String stopReason,
                           long sequence, long startedAt, long lastActivityAt, long serverTime,
                           int evaluatedNodes, boolean scoresTruncated, Map<String, Integer> rawScores,
                           List<CallView> calls, long omittedCalls, AnalysisMemoryGuard.Reading memory,
                           String databaseStorage, String indexStorage, AnalysisProvenance provenance,
                           Long finishedAt, long elapsedMillis, Long executionStartedAt,
                           long queueWaitMillis, long executionMillis,
                           com.taxonomy.dto.AnalysisNodeProgress nodeProgress,
                           com.taxonomy.dto.RelationSearchProgress relationProgress) { }

    /** Immutable decision made at the run's cancellation/completion linearization point. */
    public record Terminal(String status, String stopReason) {
        public String resultStatus() {
            return "COMPLETED".equals(status) ? "SUCCESS"
                    : "CANCELLED".equals(status) || "PARTIAL".equals(status) ? "PARTIAL" : "ERROR";
        }
        public String message(String previous) {
            if (stopReason == null || (previous != null && previous.contains(stopReason))) return previous;
            String stopped = stopReason + ": Analysis stopped cooperatively";
            return previous == null || previous.isBlank() ? stopped : previous + "; " + stopped;
        }
        public List<String> warnings(List<String> previous) {
            var warnings = new ArrayList<String>(previous == null ? List.of() : previous);
            if (stopReason != null && warnings.stream().noneMatch(value -> value != null && value.contains(stopReason))) {
                warnings.add(message(null));
            }
            return List.copyOf(warnings);
        }
        public void reconcile(AnalysisResult result) {
            result.setStatus(resultStatus());
            result.setWarnings(warnings(result.getWarnings()));
            result.setErrorMessage(message(result.getErrorMessage()));
        }
    }

    public Handle open(String requestedId, String owner, WorkspaceContext context,
                       AnalysisProvenance provenance) {
        Reservation reservation = provenance == null
                ? reserve(requestedId, owner, context, null)
                : awaitDurableAdmission(requestedId, owner, context, provenance);
        try {
            return reservation.open();
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    /** Durable work retains its existing persisted job if the bounded live queue is full. */
    private synchronized Reservation awaitDurableAdmission(String requestedId, String owner,
            WorkspaceContext context, AnalysisProvenance provenance) {
        Scope.of(owner, context);
        String id = requestedId == null ? UUID.randomUUID().toString() : canonicalId(requestedId);
        long started = System.nanoTime();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            }
            try {
                return reserve(id, owner, context, provenance);
            } catch (ResponseStatusException full) {
                if (full.getStatusCode().value() != 429 && full.getStatusCode().value() != 503) throw full;
            }
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= maximumQueueWaitMillis) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.TIME_LIMIT);
            }
            try { wait(250L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            }
        }
    }

    private synchronized void capacityReleased() { notifyAll(); }

    /** Reserve a waiting place without claiming an execution permit or binding thread locals. */
    public synchronized Reservation reserve(String requestedId, String owner, WorkspaceContext context,
                                            AnalysisProvenance provenance) {
        Scope scope = Scope.of(owner, context);
        String id = requestedId == null ? UUID.randomUUID().toString() : canonicalId(requestedId);
        reap();
        if (runs.containsKey(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis ID already exists");
        AnalysisAdmissionQueue.Ticket ticket;
        try { ticket = admission.reserve(owner); }
        catch (AnalysisAdmissionQueue.CapacityException full) {
            boolean ownerFull = full.reason() == AnalysisAdmissionQueue.Rejection.OWNER_QUEUE_FULL;
            throw new ResponseStatusException(ownerFull ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE,
                    ownerFull ? "Your analysis waiting queue is full; retry after a queued run starts"
                            : "Analysis waiting queue is full; retry after a queued run starts");
        }
        // Keep terminal history bounded without ever evicting accepted waiting/running jobs.
        while (runs.size() >= MAX_RETAINED) {
            String oldest = runs.entrySet().stream().filter(e -> !e.getValue().active())
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (oldest == null) break;
            runs.remove(oldest);
        }
        Run run = new Run(id, scope, provenance, ticket);
        runs.put(id, run);
        return new Reservation(run);
    }

    private synchronized void awaitWorkerAdmission(Run run) {
        while ("QUEUED".equals(run.status)) {
            try {
                if (Thread.currentThread().isInterrupted() || run.cancelled) {
                    throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
                }
                run.guard.check();
                if (run.queueExpired()) throw new AnalysisStoppedException(AnalysisStoppedException.Reason.TIME_LIMIT);
                if (run.ticket.tryStart()) { run.startExecution(); return; }
                wait(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                run.requestCancellation();
            } catch (AnalysisStoppedException stopped) {
                run.stopped(stopped.reason());
                run.finish("PARTIAL");
            }
        }
        capacityReleased();
    }

    public synchronized Snapshot snapshot(String id, String owner, WorkspaceContext context) {
        return require(id, owner, context).snapshot();
    }

    public synchronized Snapshot cancel(String id, String owner, WorkspaceContext context) {
        Run run = require(id, owner, context);
        cancelRun(run);
        return run.snapshot();
    }

    private synchronized void cancelRun(Run run) {
        run.requestCancellation();
        capacityReleased();
    }

    public synchronized CallDetail callDetail(String id, long callId, String owner, WorkspaceContext context) {
        Run run = require(id, owner, context);
        synchronized (run) {
            return run.calls.stream().filter(c -> c.id == callId)
                    .map(c -> new CallDetail(c.prompt, c.response, c.truncated, c.error,
                            c.promptLength, c.responseLength)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Call detail expired"));
        }
    }

    public synchronized List<Snapshot> recent(String owner, WorkspaceContext context, Long projectId, Long requirementId) {
        Scope scope = Scope.of(owner, context);
        reap();
        List<Snapshot> result = new ArrayList<>();
        for (Run run : runs.values()) {
            if (!run.scope.equals(scope)) continue;
            if (projectId != null && (run.provenance == null || !Objects.equals(projectId, run.provenance.projectId()))) continue;
            if (requirementId != null && (run.provenance == null || !Objects.equals(requirementId, run.provenance.requirementId()))) continue;
            result.add(run.snapshot());
        }
        return List.copyOf(result);
    }

    private Run require(String id, String owner, WorkspaceContext context) {
        Scope scope = Scope.of(owner, context);
        reap();
        Run run = runs.get(canonicalId(id));
        if (run == null || !run.scope.equals(scope)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis not found");
        }
        return run;
    }

    private void reap() {
        long now = System.currentTimeMillis();
        for (Run run : runs.values()) {
            if (run.queueExpired()) {
                run.stopped(AnalysisStoppedException.Reason.TIME_LIMIT);
                run.finish("PARTIAL");
            }
        }
        runs.entrySet().removeIf(e -> !e.getValue().active() && now - e.getValue().finishedAt > RETENTION_MILLIS);
    }

    /** Shared strict ID contract for admission headers and subsequent observation/cancellation. */
    public static String canonicalId(String id) {
        try {
            String canonical = UUID.fromString(id).toString();
            if (!canonical.equals(id)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Analysis ID must be a canonical UUID");
        }
    }

    /** A queued run owns only a waiting place and can be cancelled before a worker claims it. */
    public final class Reservation implements AutoCloseable {
        private final Run run;
        private boolean claimed, closed;

        private Reservation(Run run) { this.run = run; }

        /** Attach control only on the worker. Waiting never holds an execution permit. */
        public synchronized Handle open() {
            if (claimed || closed) throw new IllegalStateException("Analysis reservation is no longer available");
            awaitWorkerAdmission(run);
            Handle handle = new Handle(run);
            claimed = true;
            return handle;
        }

        /** Retain a transport disconnect even if a provider consumes the thread interrupt. */
        public void cancel() { cancelRun(run); }

        /** Roll back an unclaimed admission if scheduling fails; never remove a worker-owned run. */
        @Override public synchronized void close() {
            if (claimed || closed) return;
            closed = true;
            synchronized (AnalysisProgressRegistry.this) {
                run.ticket.close();
                runs.remove(run.id, run);
                capacityReleased();
            }
        }
    }

    public final class Handle implements AutoCloseable {
        private final Run run;
        private final AnalysisRunControl control;
        private boolean closed;
        private Handle(Run run) {
            this.run = run;
            control = new AnalysisRunControl(run, () -> run.cancelled, run.guard);
        }
        public String id() { return run.id; }
        public void finish(String status) { finishAndGet(status); }
        public void finish(AnalysisResult result) {
            finishAndGet(result.getStatus()).reconcile(result);
        }
        public Terminal finishAndGet(String status) {
            Terminal terminal = run.finish(status);
            // Do not acquire the registry monitor while holding the run monitor.
            capacityReleased();
            return terminal;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { if (run.active()) run.finish("ERROR"); }
            finally {
                control.close();
                capacityReleased();
            }
        }
    }

    private static final class Call {
        final long id, startedAt;
        final long startedNanos = System.nanoTime();
        final String provider, node;
        String status = "STARTED", prompt = "", response = "", error = "";
        int promptLength, responseLength;
        long duration;
        boolean truncated;
        Call(long id, String provider, String node) {
            this.id = id; this.provider = bounded(provider, 64); this.node = bounded(node, 256);
            startedAt = System.currentTimeMillis();
        }
        CallView view() { return new CallView(id, provider, node, status, startedAt, duration); }
    }

    private final class Run implements AnalysisRunControl.Observer {
        final String id;
        final Scope scope;
        final AnalysisProvenance provenance;
        final AnalysisAdmissionQueue.Ticket ticket;
        final long startedAt = System.currentTimeMillis();
        final long startedNanos = System.nanoTime();
        Long executionStartedAt;
        long executionStartedNanos, frozenQueueMillis, finishedElapsedMillis;
        final AnalysisMemoryGuard guard = new AnalysisMemoryGuard(policy, AnalysisMemoryGuard::heapSample,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        final ArrayDeque<Call> calls = new ArrayDeque<>();
        final Map<String, Integer> scores = new LinkedHashMap<>();
        final AnalysisNodeTracker nodeTracker = new AnalysisNodeTracker();
        com.taxonomy.dto.RelationSearchProgress relationProgress;
        volatile String status = "QUEUED";
        volatile boolean cancelled;
        volatile long finishedAt;
        String phase = "QUEUED", node = "", stopReason;
        long sequence = 1, lastActivityAt = startedAt, callSequence, omittedCalls;
        boolean scoresTruncated;
        Run(String id, Scope scope, AnalysisProvenance provenance, AnalysisAdmissionQueue.Ticket ticket) {
            this.id = id; this.scope = scope; this.provenance = provenance; this.ticket = ticket;
        }
        @Override public String operationId() { return id; }
        boolean active() { return "QUEUED".equals(status) || "RUNNING".equals(status) || "CANCELLING".equals(status); }
        boolean queueExpired() {
            return "QUEUED".equals(status)
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) >= maximumQueueWaitMillis;
        }
        synchronized void startExecution() {
            executionStartedAt = System.currentTimeMillis();
            executionStartedNanos = System.nanoTime();
            frozenQueueMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, executionStartedNanos - startedNanos));
            status = "RUNNING";
            phase("PREPARING", null);
        }
        synchronized void requestCancellation() {
            if (active()) {
                boolean queued = "QUEUED".equals(status);
                cancelled = true;
                status = "CANCELLING";
                touch();
                if (queued) finish("CANCELLED");
            }
        }
        void touch() { sequence++; lastActivityAt = System.currentTimeMillis(); }
        @Override public synchronized void planNodes(List<com.taxonomy.dto.TaxonomyNodeDto> tree) {
            nodeTracker.plan(tree); touch();
        }
        @Override public synchronized void assessment(LlmCallDetail detail) {
            nodeTracker.accept(detail); touch();
        }
        @Override public synchronized void relations(com.taxonomy.dto.RelationSearchProgress progress) {
            relationProgress = progress; touch();
        }
        @Override public synchronized void checkpoint() {
            if (!active() && stopReason != null) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.valueOf(stopReason));
            }
        }
        @Override public synchronized void phase(String phase, String node) {
            this.phase = bounded(phase, 64);
            if (node != null) this.node = bounded(node, 256);
            touch();
        }
        @Override public synchronized long started(String provider, String node) {
            if (calls.size() >= MAX_CALLS) { calls.removeFirst(); omittedCalls++; }
            Call call = new Call(++callSequence, provider, node);
            calls.addLast(call);
            phase("LLM_PREPARING", node);
            return call.id;
        }
        @Override public synchronized void prepared(long id, String prompt) {
            if (!active()) return;
            Call call = calls.stream().filter(c -> c.id == id).findFirst().orElse(null);
            if (call == null || !"STARTED".equals(call.status)) return;
            call.promptLength = length(prompt);
            call.prompt = bounded(prompt, MAX_TEXT);
            call.truncated = call.promptLength > MAX_TEXT;
            touch();
        }
        @Override public synchronized void completed(long id, LlmCallDetail detail, long duration) {
            nodeTracker.accept(detail);
            Call call = calls.stream().filter(c -> c.id == id).findFirst().orElse(null);
            if (call != null) {
                call.status = detail.getError() == null || detail.getError().isBlank() ? "COMPLETED" : "FAILED";
                call.duration = duration;
                call.promptLength = length(detail.getPrompt());
                call.responseLength = length(detail.getRawResponse());
                call.error = bounded(detail.getError(), 1024);
                call.prompt = bounded(detail.getPrompt(), MAX_TEXT);
                call.response = bounded(detail.getRawResponse(), MAX_TEXT);
                call.truncated = call.promptLength > MAX_TEXT || call.responseLength > MAX_TEXT;
            }
            if ((detail.getError() == null || detail.getError().isBlank()) && detail.getScores() != null)
                detail.getScores().forEach((code, score) -> {
                if (code == null || score == null) return;
                if (scores.containsKey(code) || scores.size() < MAX_SCORES) scores.put(code, score);
                else scoresTruncated = true;
            });
            phase("SCORING", null);
        }
        @Override public synchronized void stoppedAfterResponse(long id, LlmCallDetail detail,
                long duration, AnalysisStoppedException.Reason reason) {
            completed(id, detail, duration);
            calls.stream().filter(call -> call.id == id).findFirst()
                    .ifPresent(call -> call.status = "STOPPED");
            stopped(reason);
        }
        @Override public synchronized void failed(long id, String failure, long duration) {
            calls.stream().filter(c -> c.id == id).findFirst().ifPresent(c -> {
                c.status = "FAILED";
                c.duration = duration;
                c.error = bounded(failure, 1024);
            });
            phase("LLM_FAILED", null);
        }
        @Override public synchronized void stopped(AnalysisStoppedException.Reason reason) {
            if (!active()) return;
            if (stopReason == null) stopReason = reason.name();
            long now = System.nanoTime();
            calls.stream().filter(call -> "STARTED".equals(call.status)).forEach(call -> {
                call.status = "STOPPED";
                call.duration = TimeUnit.NANOSECONDS.toMillis(Math.max(0, now - call.startedNanos));
            });
            phase("STOPPING", null);
        }
        synchronized Terminal finish(String resultStatus) {
            if (!active()) return new Terminal(status, stopReason);
            // cancel() and finish() share the run monitor: a cancellation accepted first wins.
            if (cancelled && stopReason == null) stopped(AnalysisStoppedException.Reason.CANCELLED);
            String terminalStatus = "CANCELLED".equals(stopReason) || "CANCELLED".equals(resultStatus) ? "CANCELLED"
                    : stopReason != null ? "PARTIAL"
                    : "SUCCESS".equals(resultStatus) ? "COMPLETED"
                    : "PARTIAL".equals(resultStatus) ? "PARTIAL" : "ERROR";
            phase = "FINISHED";
            finishedAt = System.currentTimeMillis();
            finishedElapsedMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            touch();
            ticket.close();
            // Publish timestamps and terminal metadata before making the run inactive.
            status = terminalStatus;
            return new Terminal(status, stopReason);
        }
        synchronized Snapshot snapshot() {
            long elapsed = active() ? Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L) : finishedElapsedMillis;
            long queued = executionStartedAt == null ? elapsed : frozenQueueMillis;
            return new Snapshot(id, status, phase, node, stopReason, sequence, startedAt, lastActivityAt,
                    System.currentTimeMillis(), nodeTracker.assessed(), scoresTruncated, Map.copyOf(scores),
                    calls.stream().map(Call::view).toList(), omittedCalls, guard.reading(),
                    databaseStorage, indexStorage, provenance, active() ? null : finishedAt,
                    elapsed, executionStartedAt, queued, executionStartedAt == null ? 0L : Math.max(0L, elapsed - queued),
                    nodeTracker.snapshot(), relationProgress);
        }
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }
    private static String bounded(String value, int limit) {
        if (value == null) return "";
        if (value.length() <= limit) return value;
        String marker = "\n[truncated]";
        return value.substring(0, limit - marker.length()) + marker;
    }
}
