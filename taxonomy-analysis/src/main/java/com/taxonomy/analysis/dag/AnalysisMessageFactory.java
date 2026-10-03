package com.taxonomy.analysis.dag;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Builds consistent envelopes for one operation's tasks, completions and events. */
public final class AnalysisMessageFactory {

    private final AnalysisOperationContext operation;
    private final Clock clock;

    public AnalysisMessageFactory(AnalysisOperationContext operation, Clock clock) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AnalysisOperationContext operation() {
        return operation;
    }

    public AnalysisTaskMessage task(AnalysisTaskGraph.Node node) {
        if (!node.id().operationId().equals(operation.operationId())) {
            throw new IllegalArgumentException("Task belongs to another operation");
        }
        return switch (node.type()) {
            case SUBTAXONOMY_ANALYSIS -> new SubtaxonomyAnalysisTask(
                    envelope(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_TASK, node.id(), node.type(), node.roots()),
                    node.roots().get(0));
            case RELATION_ANALYSIS -> new RelationAnalysisTask(
                    envelope(AnalysisMessageType.RELATION_ANALYSIS_TASK, node.id(), node.type(), node.roots()),
                    node.roots(), node.prerequisites());
        };
    }

    public SubtaxonomyAnalysisCompleted completed(SubtaxonomyAnalysisTask task, AnalysisTaskOutcome outcome,
                                                  Integer rootScore, int scoredNodes, String stopReason) {
        return new SubtaxonomyAnalysisCompleted(
                task.envelope().derive(AnalysisMessageType.SUBTAXONOMY_ANALYSIS_COMPLETED, clock.instant()),
                task.root(), outcome, rootScore, scoredNodes, stopReason);
    }

    public RelationAnalysisCompleted completed(RelationAnalysisTask task, AnalysisTaskOutcome outcome,
                                               int relationCount, String stopReason) {
        return new RelationAnalysisCompleted(
                task.envelope().derive(AnalysisMessageType.RELATION_ANALYSIS_COMPLETED, clock.instant()),
                outcome, relationCount, stopReason);
    }

    public AnalysisProgressEvent progress(long sequence, AnalysisProgressPhase phase, AnalysisTaskGraph.Node node,
                                          int completedTasks, int totalTasks) {
        AnalysisEnvelope envelope = node == null
                ? envelope(AnalysisMessageType.ANALYSIS_PROGRESS, null, null, List.of())
                : envelope(AnalysisMessageType.ANALYSIS_PROGRESS, node.id(), node.type(), node.roots());
        return new AnalysisProgressEvent(envelope, sequence, phase, completedTasks, totalTasks);
    }

    public AnalysisCancellationEvent cancellation(String reason) {
        return new AnalysisCancellationEvent(
                envelope(AnalysisMessageType.ANALYSIS_CANCELLATION, null, null, List.of()), reason);
    }

    private AnalysisEnvelope envelope(AnalysisMessageType type, AnalysisTaskId taskId, AnalysisTaskType taskType,
                                      List<TaxonomyShardRoot> roots) {
        return new AnalysisEnvelope(AnalysisEnvelope.SCHEMA_VERSION, type, operation.operationId(), taskId,
                taskType, operation.authority(), operation.requirement(), roots, 1,
                null, operation.correlationId(), clock.instant(), null);
    }
}
