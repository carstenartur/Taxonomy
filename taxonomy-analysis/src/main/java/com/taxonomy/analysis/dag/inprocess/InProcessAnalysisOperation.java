package com.taxonomy.analysis.dag.inprocess;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisEventPublisher;
import com.taxonomy.analysis.dag.AnalysisMessageFactory;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisProgressPhase;
import com.taxonomy.analysis.dag.AnalysisTaskGraph;
import com.taxonomy.analysis.dag.AnalysisTaskHandler;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.AnalysisTaskType;
import com.taxonomy.analysis.dag.RelationAnalysisCompleted;
import com.taxonomy.analysis.dag.RelationAnalysisTask;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisCompleted;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-confined coordinator of one analysis operation's task graph for the
 * single-node compatibility path.
 *
 * <p>The coordinator dispatches ready tasks in deterministic graph order through
 * an {@link InProcessAnalysisTaskTransport}, records completions idempotently by
 * task identity, emits known-total progress and stops dispatching after a
 * cooperative stop. It is bound to the creating thread like
 * {@code AnalysisRunControl}; it is not a cluster authority.</p>
 */
public final class InProcessAnalysisOperation implements AutoCloseable {

    private static final ThreadLocal<InProcessAnalysisOperation> CURRENT = new ThreadLocal<>();

    private final InProcessAnalysisOperation previous;
    private final AnalysisMessageFactory messages;
    private final AnalysisEventPublisher events;
    private final Map<AnalysisTaskId, AnalysisCompletionMessage> completions = new LinkedHashMap<>();
    private final Set<AnalysisTaskId> dispatched = new LinkedHashSet<>();
    private AnalysisTaskGraph graph;
    private long sequence;
    private boolean stopped;
    private boolean closed;

    private InProcessAnalysisOperation(AnalysisOperationContext context, AnalysisEventPublisher events, Clock clock) {
        this.messages = new AnalysisMessageFactory(context, clock);
        this.events = events == null ? AnalysisEventPublisher.NONE : events;
        this.previous = CURRENT.get();
        CURRENT.set(this);
    }

    /** Bind a new operation to the current thread until {@link #close()}. */
    public static InProcessAnalysisOperation open(AnalysisOperationContext context, AnalysisEventPublisher events) {
        return open(context, events, Clock.systemUTC());
    }

    public static InProcessAnalysisOperation open(AnalysisOperationContext context, AnalysisEventPublisher events,
                                                  Clock clock) {
        return new InProcessAnalysisOperation(Objects.requireNonNull(context, "context"), events, clock);
    }

    public static Optional<InProcessAnalysisOperation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public AnalysisOperationContext context() {
        return messages.operation();
    }

    public Optional<AnalysisTaskGraph> graph() {
        return Optional.ofNullable(graph);
    }

    /** True when this operation has not yet planned scoring work and can therefore adopt a plan. */
    public boolean canPlan() {
        return graph == null;
    }

    /** Plan the operation's graph exactly once. */
    public AnalysisTaskGraph plan(Collection<TaxonomyShardRoot> orderedRoots, boolean includeRelations) {
        if (graph != null) throw new IllegalStateException("Operation graph is already planned");
        graph = AnalysisTaskGraph.plan(context().operationId(), orderedRoots, includeRelations);
        progress(AnalysisProgressPhase.PLANNED, null);
        return graph;
    }

    /**
     * Dispatch all ready sub-taxonomy tasks in plan order; stop dispatching after a
     * completion that stops the operation. Returns the completions observed.
     */
    public List<SubtaxonomyAnalysisCompleted> runSubtaxonomyTasks(
            AnalysisTaskHandler<SubtaxonomyAnalysisTask, SubtaxonomyAnalysisCompleted> handler) {
        requirePlanned();
        var observed = new ArrayList<SubtaxonomyAnalysisCompleted>();
        var transport = new InProcessAnalysisTaskTransport(handler, null, completion -> {
            record(completion);
            observed.add((SubtaxonomyAnalysisCompleted) completion);
        });
        for (AnalysisTaskGraph.Node node : graph.ready(AnalysisTaskType.SUBTAXONOMY_ANALYSIS,
                completions.keySet(), dispatched)) {
            if (stopped) break;
            dispatch(transport, node);
        }
        return List.copyOf(observed);
    }

    /**
     * Dispatch ready relation tasks. When scoring was produced outside this graph,
     * a relation-only plan over {@code targetRoots} is adopted first.
     */
    public List<RelationAnalysisCompleted> runRelationTasks(
            Collection<TaxonomyShardRoot> targetRoots,
            AnalysisTaskHandler<RelationAnalysisTask, RelationAnalysisCompleted> handler) {
        if (graph == null) {
            graph = AnalysisTaskGraph.relationsOnly(context().operationId(), targetRoots);
            progress(AnalysisProgressPhase.PLANNED, null);
        }
        var observed = new ArrayList<RelationAnalysisCompleted>();
        if (stopped) return List.of();
        var transport = new InProcessAnalysisTaskTransport(null, handler, completion -> {
            record(completion);
            observed.add((RelationAnalysisCompleted) completion);
        });
        for (AnalysisTaskGraph.Node node : graph.ready(AnalysisTaskType.RELATION_ANALYSIS,
                completions.keySet(), dispatched)) {
            if (stopped) break;
            dispatch(transport, node);
        }
        return List.copyOf(observed);
    }

    private void dispatch(InProcessAnalysisTaskTransport transport, AnalysisTaskGraph.Node node) {
        dispatched.add(node.id());
        progress(AnalysisProgressPhase.TASK_DISPATCHED, node);
        transport.publish(messages.task(node));
    }

    private void record(AnalysisCompletionMessage completion) {
        AnalysisCompletionMessage existing = completions.putIfAbsent(completion.taskId(), completion);
        if (existing != null) return;
        var node = graph.node(completion.taskId()).orElseThrow(
                () -> new IllegalStateException("Completion for an unplanned task"));
        if (completion.stopsOperation()) {
            stopped = true;
            progress(AnalysisProgressPhase.TASK_COMPLETED, node);
            progress(AnalysisProgressPhase.OPERATION_STOPPED, null);
            return;
        }
        progress(AnalysisProgressPhase.TASK_COMPLETED, node);
        if (completions.size() == graph.totalTasks()) {
            progress(AnalysisProgressPhase.OPERATION_COMPLETED, null);
        }
    }

    private void progress(AnalysisProgressPhase phase, AnalysisTaskGraph.Node node) {
        int total = graph == null ? 0 : graph.totalTasks();
        events.progress(messages.progress(++sequence, phase, node, Math.min(completions.size(), total), total));
    }

    private void requirePlanned() {
        if (graph == null) throw new IllegalStateException("Operation graph is not planned");
    }

    public AnalysisMessageFactory messages() {
        return messages;
    }

    /** Recorded completions in completion order. */
    public Map<AnalysisTaskId, AnalysisCompletionMessage> completions() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(completions));
    }

    /** Dispatched task identities in dispatch order. */
    public Set<AnalysisTaskId> dispatched() {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(dispatched));
    }

    public boolean stopped() {
        return stopped;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (CURRENT.get() == this) {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
