package com.taxonomy.analysis.dag.inprocess;

import com.taxonomy.analysis.dag.AnalysisCompletionMessage;
import com.taxonomy.analysis.dag.AnalysisCompletionPublisher;
import com.taxonomy.analysis.dag.AnalysisTaskHandler;
import com.taxonomy.analysis.dag.AnalysisTaskId;
import com.taxonomy.analysis.dag.AnalysisTaskMessage;
import com.taxonomy.analysis.dag.AnalysisTaskPublisher;
import com.taxonomy.analysis.dag.RelationAnalysisCompleted;
import com.taxonomy.analysis.dag.RelationAnalysisTask;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisCompleted;
import com.taxonomy.analysis.dag.SubtaxonomyAnalysisTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single-node compatibility transport: delivers a task synchronously on the
 * publishing thread, so request-scoped provider selection, run control and
 * checkpoint sessions behave exactly as in the former monolithic run.
 *
 * <p>Effects are idempotent by {@link AnalysisTaskId}: publishing a task whose
 * completion is already recorded republishes that completion instead of executing
 * the work again.</p>
 */
public final class InProcessAnalysisTaskTransport implements AnalysisTaskPublisher {

    private final AnalysisTaskHandler<SubtaxonomyAnalysisTask, SubtaxonomyAnalysisCompleted> subtaxonomyHandler;
    private final AnalysisTaskHandler<RelationAnalysisTask, RelationAnalysisCompleted> relationHandler;
    private final AnalysisCompletionPublisher completions;
    private final Map<AnalysisTaskId, AnalysisCompletionMessage> recorded = new LinkedHashMap<>();
    private int executions;
    private int idempotentReplays;

    public InProcessAnalysisTaskTransport(
            AnalysisTaskHandler<SubtaxonomyAnalysisTask, SubtaxonomyAnalysisCompleted> subtaxonomyHandler,
            AnalysisTaskHandler<RelationAnalysisTask, RelationAnalysisCompleted> relationHandler,
            AnalysisCompletionPublisher completions) {
        this.subtaxonomyHandler = subtaxonomyHandler;
        this.relationHandler = relationHandler;
        this.completions = Objects.requireNonNull(completions, "completions");
    }

    @Override
    public void publish(AnalysisTaskMessage task) {
        Objects.requireNonNull(task, "task");
        AnalysisCompletionMessage previous = recorded.get(task.taskId());
        if (previous != null) {
            idempotentReplays++;
            completions.publish(previous);
            return;
        }
        AnalysisCompletionMessage completion = switch (task) {
            case SubtaxonomyAnalysisTask subtaxonomy -> handle(subtaxonomyHandler, subtaxonomy);
            case RelationAnalysisTask relation -> handle(relationHandler, relation);
        };
        if (completion == null || !task.taskId().equals(completion.taskId())) {
            throw new IllegalStateException("Handler returned no completion for " + task.taskId());
        }
        executions++;
        recorded.put(task.taskId(), completion);
        completions.publish(completion);
    }

    private static <T extends AnalysisTaskMessage, C extends AnalysisCompletionMessage> C handle(
            AnalysisTaskHandler<T, C> handler, T task) {
        if (handler == null) {
            throw new IllegalStateException("No in-process handler for " + task.taskType());
        }
        return handler.handle(task);
    }

    /** Number of task executions (excluding idempotent replays). */
    public int executions() {
        return executions;
    }

    public int idempotentReplays() {
        return idempotentReplays;
    }
}
